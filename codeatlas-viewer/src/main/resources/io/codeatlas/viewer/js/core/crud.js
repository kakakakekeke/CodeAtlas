// CRUD 매트릭스 집계. DOM을 쓰지 않는다.
// 규칙과 표기는 표준 산출물(codeatlas-export의 CrudMatrix, `crud/table-*.csv`)과 같다(F-015-R20, R21, R25, R26):
//  - R은 테이블 수준 READS가 있을 때만 붙는다. 컬럼으로 가는 READS는 세지 않는다.
//  - 대문자는 주체가 직접 하는 접근, 소문자는 DB 루틴(프로시저·함수·트리거)을 거쳐서만 닿는 접근이다. 같은 글자가 둘 다면 대문자다.
//  - 대상 테이블을 정적으로 알 수 없는 동적 구문의 접근은 버리지 않고 마지막 열 `(동적)`에 모은다.
//  - 추론한 관계를 거쳐서만 닿은 칸은 끝에 `?`가 붙는다(F-015-R52: `R?`, `ru?`). 그 테이블에 닿는 확정 경로가 하나라도 있으면 붙지 않는다.
//    엔드포인트 기준은 호출 사슬의 걸음(콜트리 줄의 inference), 메서드 기준은 EXECUTES 자체의 신뢰도로 정한다. 구문·루틴 기준에는 붙지 않는다.
(function (root) {
  'use strict';
  if (typeof module === 'object' && module.exports) { require('./graph.js'); require('./calltree.js'); }
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var G = CA.graph;
  var CT = CA.calltree;

  /** 글자의 표시 순서. W는 종류를 모르는 쓰기다. */
  var ORDER = 'CRUDW';
  var WRITE_OPS = { INSERT: 'C', UPDATE: 'U', DELETE: 'D', MERGE: 'CU' };
  /** 대상 테이블을 정적으로 알 수 없는 접근을 모으는 열의 이름이자 칸의 키. */
  var DYNAMIC = '(동적)';
  var DYNAMIC_PREFIX = 'unresolved:sql-dynamic:';
  /** 호출 사슬과 루틴 호출을 따라갈 기본 최대 걸음 수(표준 산출물의 `export.crud.depth` 기본값). */
  var DEFAULT_MAX_DEPTH = 20;
  /** 추론한 관계를 거쳐서만 닿은 칸의 끝에 붙는 표시. */
  var INFERRED_MARK = '?';

  /** 행의 기준. 표준 산출물의 네 파일(table-statement, table-method, table-endpoint, table-routine)과 같다. */
  var ROW_KINDS = [
    { key: 'statement', label: 'SQL 구문', csvTitle: '구문', nodeKind: 'SQL_STATEMENT',
      description: 'SQL 구문마다 한 행. 테이블에 직접 접근하지 않고 루틴만 부르는 구문(CALL)의 행도 있다' },
    { key: 'method', label: 'Java 메서드', csvTitle: '메서드', nodeKind: 'JAVA_METHOD',
      description: '구문(또는 루틴)을 직접 실행(EXECUTES)하는 메서드마다 한 행' },
    { key: 'endpoint', label: '엔드포인트', csvTitle: '엔드포인트', nodeKind: 'HTTP_ENDPOINT',
      description: '호출 사슬(콜트리와 같은 규칙, 화면 이동은 빼고)로 구문에 닿는 엔드포인트마다 한 행. 트리거가 쓰는 테이블은 그 트리거를 일으키는 쓰기를 거칠 때만 넣는다' },
    { key: 'routine', label: '루틴', csvTitle: '루틴', nodeKind: 'DB_ROUTINE',
      description: '구문을 가진 루틴(프로시저, 함수, 트리거)마다 한 행. 부르는 루틴의 구문까지 넣는다' }
  ];

  /** 범례. 화면의 표 위에 그대로 보인다. */
  var LEGEND = [
    { key: 'C R U D', description: '삽입 · 조회 · 수정 · 삭제 (W는 종류를 모르는 변경). MERGE는 C와 U다. R은 테이블을 실제로 읽을 때(테이블 수준 READS)만 붙는다' },
    { key: '대문자', description: '행의 주체가 직접 하는 접근이다(구문은 자기 SQL, 메서드는 직접 실행한 구문, 루틴은 자기 본문, 엔드포인트는 호출 사슬로 닿은 구문)' },
    { key: '소문자', description: 'DB 루틴(프로시저·함수·트리거)을 거쳐서만 닿는 간접 접근이다. 같은 글자가 직접·간접 둘 다면 대문자만 적는다' },
    { key: 'Ru', description: '읽는 법의 예: 직접 읽고(R), 루틴을 거쳐 수정한다(u). 칸을 대문자로 바꾸면 직접·간접을 합친 CRUD다' },
    { key: INFERRED_MARK, description: '칸 끝의 ?는 추론한 관계를 거쳐서만 닿았다는 뜻이다(예: R?, ru?). 그 테이블에 닿는 확정 경로가 하나라도 있으면 붙지 않는다. 엔드포인트 기준은 콜트리에서 추론(~)·수신자?·트리거?가 붙는 줄을 거쳤을 때(요청 전 처리, forward, 수신자 타입이 대상 하나를 고른 상속 구문은 확정으로 본다), 메서드 기준은 구문으로 가는 EXECUTES 자체가 추론일 때(상속 매퍼 메서드 등)다. 구문·루틴 기준에는 붙지 않는다' },
    { key: DYNAMIC, description: '대상 테이블을 정적으로 알 수 없는 구문(동적 SQL)의 접근을 모은 열이다. 어느 테이블에도 넣을 수 없어 따로 둔다. 구문 기준에서는 표 아래에 그 구문의 목록이 나온다' }
  ];

  function rowKindOf(key) {
    for (var i = 0; i < ROW_KINDS.length; i++) if (ROW_KINDS[i].key === key) return ROW_KINDS[i];
    return ROW_KINDS[0];
  }

  /** 글자 집합(객체의 키)을 C, R, U, D, W 순의 문자열로. 대문자(직접)가 있으면 대문자, 소문자(간접)만 있으면 소문자. 예: `Ru`. */
  function format(letters) {
    var s = '';
    for (var i = 0; i < ORDER.length; i++) {
      var c = ORDER.charAt(i);
      if (letters[c]) s += c;
      else if (letters[c.toLowerCase()]) s += c.toLowerCase();
    }
    return s;
  }

  function kindOf(index, id) {
    var n = index.node(id);
    return n ? n.kind : G.kindOfId(id) || 'UNRESOLVED';
  }

  /** 엣지가 대상 테이블을 알 수 없는 동적 접근인지: `unresolved:sql-dynamic:`으로 가는 READS/WRITES. */
  function isDynamicAccess(index, e) {
    if (e.kind !== 'READS' && e.kind !== 'WRITES') return false;
    if (String(e.to).indexOf(DYNAMIC_PREFIX) === 0) return true;
    var target = index.node(e.to);
    return !!target && target.kind === 'UNRESOLVED' && target.attrs.category === 'sql-dynamic';
  }

  /** READS는 R. WRITES는 attrs.op로 정하고, 없으면 구문의 statementType으로 정한다. 그래도 모르면 W. */
  function lettersOf(index, e) {
    if (e.kind === 'READS') return 'R';
    var normalize = function (op) {
      var upper = op == null ? '' : String(op).trim().toUpperCase();
      return WRITE_OPS[upper] ? upper : null;
    };
    var op = normalize(e.attrs && e.attrs.op);
    if (!op) {
      var stmt = index.node(e.from);
      op = normalize(stmt && stmt.attrs.statementType);
    }
    return op ? WRITE_OPS[op] : 'W';
  }

  /** 그래프의 데이터 적재 구문(dataLoad=true) ID. 스크립트의 리터럴 INSERT를 파일·테이블 단위로 묶은 것이라 구문 단위 집계에서는 기본으로 뺀다. */
  function dataLoadIds(index) {
    var ids = new Set();
    index.nodesOfKind('SQL_STATEMENT').forEach(function (n) { if (n.attrs.dataLoad === true) ids.add(n.id); });
    return ids;
  }

  /**
   * 구문마다 테이블에 직접 하는 일. Map(구문 ID → Map(테이블 ID 또는 DYNAMIC → {글자: true})).
   * 컬럼으로 가는 READS는 R로 세지 않는다: UPDATE·DELETE·MERGE의 대상 테이블에는 테이블 수준 READS가 없으므로(계약 design 3.4)
   * `DELETE FROM T WHERE x = ?`는 D다. 컬럼으로 가는 WRITES는 테이블로 접는다(op가 붙어 있다).
   * excluded(구문 ID 집합)에 든 구문의 접근은 넣지 않는다(데이터 적재 구문).
   */
  function accessByStatement(index, excluded) {
    var by = new Map();
    var skip = excluded && excluded.size ? excluded : null;
    index.edges.forEach(function (e) {
      if (e.kind !== 'READS' && e.kind !== 'WRITES') return;
      if (skip && skip.has(e.from)) return;
      var table = null;
      var letters;
      if (isDynamicAccess(index, e)) {
        table = DYNAMIC;
        letters = lettersOf(index, e);
      } else {
        var target = index.node(e.to);
        if (target && (target.kind === 'DB_TABLE' || target.kind === 'DB_VIEW')) table = e.to;
        else if (target && target.kind === 'DB_COLUMN') {
          if (e.kind === 'READS') return;
          table = index.tableOfColumn(e.to);
        }
        if (!table) return;
        letters = lettersOf(index, e);
      }
      var m = by.get(e.from);
      if (!m) by.set(e.from, m = new Map());
      var cell = m.get(table);
      if (!cell) m.set(table, cell = {});
      for (var i = 0; i < letters.length; i++) cell[letters.charAt(i)] = true;
    });
    return by;
  }

  /** 접근 글자를 주체에 더한다. indirect면 소문자로 더한다(출력할 때 같은 글자의 대문자가 있으면 대문자가 이긴다). */
  function merge(bySubject, subjectId, access, indirect) {
    if (!access) return;
    var target = bySubject.get(subjectId);
    if (!target) bySubject.set(subjectId, target = new Map());
    access.forEach(function (letters, table) {
      var cell = target.get(table);
      if (!cell) target.set(table, cell = {});
      Object.keys(letters).forEach(function (c) { cell[indirect ? c.toLowerCase() : c] = true; });
    });
  }

  /** 루틴이 직접 가진 구문(CONTAINS, ID 순). */
  function statementsOf(index, routineId) {
    var out = [];
    index.out(routineId).forEach(function (e) {
      if (e.kind === 'CONTAINS' && kindOf(index, e.to) === 'SQL_STATEMENT' && out.indexOf(e.to) < 0) out.push(e.to);
    });
    return out.sort(G.compareText);
  }

  /** 구문이나 루틴이 CALLS로 부르는 루틴(중복 없이). */
  function calledRoutines(index, callerIds) {
    var routines = [];
    callerIds.forEach(function (caller) {
      index.out(caller).forEach(function (e) {
        if (e.kind === 'CALLS' && kindOf(index, e.to) === 'DB_ROUTINE' && routines.indexOf(e.to) < 0) routines.push(e.to);
      });
    });
    return routines;
  }

  /** 구문이 DB 루틴(프로시저·함수·트리거)의 본문에 든 것인지. */
  function inRoutine(index, statementId) {
    var incoming = index.in(statementId);
    for (var i = 0; i < incoming.length; i++) {
      if (incoming[i].kind === 'CONTAINS' && kindOf(index, incoming[i].from) === 'DB_ROUTINE') return true;
    }
    return false;
  }

  /**
   * 루틴들과 그 루틴이 (구문 안에서든 본문에서든) 부르는 루틴의 구문이 하는 접근을 주체의 간접 접근(소문자)으로 더한다.
   * hops: 처음 루틴들에서 더 따라갈 루틴 호출의 걸음 수. 음수면 아무것도 더하지 않는다.
   * skip: 다시 들어가지 않을 루틴(주체 자신). 재귀와 상호 호출에서 자기 구문을 간접으로 세지 않게 한다.
   */
  function mergeRoutines(index, bySubject, subjectId, routines, hops, skip, byStatement) {
    var seen = new Set(skip || []);
    var frontier = [];
    routines.forEach(function (r) { if (!seen.has(r)) { seen.add(r); frontier.push(r); } });
    for (var hop = 0; hop <= hops && frontier.length; hop++) {
      var next = [];
      frontier.forEach(function (routine) {
        var statements = statementsOf(index, routine);
        statements.forEach(function (s) { merge(bySubject, subjectId, byStatement.get(s), true); });
        calledRoutines(index, statements.concat([routine])).forEach(function (called) {
          if (!seen.has(called)) { seen.add(called); next.push(called); }
        });
      });
      frontier = next;
    }
  }

  /**
   * 엔드포인트에서 아래 방향으로 닿는 노드. 콜트리와 같은 규칙(구현 전개와 주입 좁히기, 트리거의 사건 대조, 수신자 문맥으로 팬아웃 좁히기)으로
   * 따라가되 화면 흐름(RENDERS, REQUESTS, MATCHES)과 redirect로 넘어가는 핸들러(별개의 요청, F-015-R42)는 따라가지 않는다. 뷰어의 콜트리만 하는 "뷰 → 정의 쿼리"도 따라가지 않는다(표준 산출물과 같게).
   * 접근자·소스 없는 메서드는 접지 않는다(표준 산출물의 매트릭스도 접지 않는다).
   * walk: { cache(자식 계산 결과), opts(콜트리 옵션) }. 엔드포인트 여럿이 같은 메서드를 지나므로 자식 계산은 문맥별로 한 번만 한다.
   */
  function reachable(index, startId, maxDepth, walk) {
    // 노드 ID → 추론 걸음을 거쳐서만 닿았으면 true(확정 경로가 하나라도 있으면 false)
    var nodes = new Map();
    // 방문 표시의 키 → 추론으로만 닿았는지. 추론으로 먼저 닿은 자리에 확정 경로로 다시 닿으면 그 아래를 다시 따라간다.
    var seen = new Map([[startId, false]]);
    var frontier = [{ id: startId, parent: null, via: null, key: startId, inferred: false }];
    for (var depth = 0; depth < maxDepth && frontier.length; depth++) {
      var next = [];
      for (var f = 0; f < frontier.length; f++) {
        var v = frontier[f];
        // 같은 자리에 확정 경로로 다시 닿았으면 그 방문이 아래를 맡는다.
        if (v.inferred && !seen.get(v.key)) continue;
        // 방문 표시의 키가 곧 자식 계산의 문맥이다: 문맥(직전 노드, 수신자 타입)에 따라 아래가 달라지는 노드만 문맥이 키에 들어 있다.
        var links = walk.cache.get(v.key);
        if (!links) walk.cache.set(v.key, links = CT.childLinks(index, v.id, 'callees', walk.opts, v.via, v.parent));
        for (var i = 0; i < links.length; i++) {
          var link = links[i];
          if (link.kind === 'RENDERS' || link.kind === 'REQUESTS' || link.kind === 'MATCHES') continue;
          // redirect는 브라우저가 새로 보내는 별개의 요청이라 따라가지 않는다(forward는 같은 요청이라 따라간다). 레이아웃도 따라가지 않는다.
          if (CT.isRedirectLink(link) || CT.isLayoutLink(link)) continue;
          if (link.kind === 'CONTAINS' && kindOf(index, v.id) === 'DB_VIEW') continue;
          var k = (CT.contextSensitive(index, link.nodeId) ? link.nodeId + '|' + v.id + '|' + link.kind : link.nodeId) + CT.arrivalKey(index, link.nodeId, link);
          var inferred = v.inferred || link.inference != null;
          // 뷰어는 같은 노드로 가는 줄을 관계 이름별로 따로 두므로(READS와 WRITES 등) 노드의 상태는 줄들 가운데 가장 확실한 것이다.
          nodes.set(link.nodeId, nodes.has(link.nodeId) ? nodes.get(link.nodeId) && inferred : inferred);
          var before = seen.get(k);
          if (before !== undefined && !(before && !inferred)) continue;
          seen.set(k, inferred);
          next.push({ id: link.nodeId, parent: v.id, via: link, key: k, inferred: inferred });
        }
      }
      frontier = next;
    }
    return nodes;
  }

  /** 엔드포인트 기준 탐색에 쓰는 상태. 여러 엔드포인트가 함께 쓴다. */
  function createWalk(includeDataLoad) {
    return { cache: new Map(), opts: { showAccessors: true, showExternal: true, includeDataLoad: includeDataLoad === true } };
  }

  /**
   * 구문을 실행하는 메서드에서 호출을 거슬러 올라간 메서드(와 그것이 재정의한 상위 메서드)를 found(메서드 ID)로 알린다.
   * 뷰어만 하는 "호출 경유 포함"에 쓴다.
   */
  function walkUpMethods(index, methodId, found) {
    var seen = new Set([methodId]);
    var stack = [methodId];
    while (stack.length) {
      var id = stack.pop();
      index.in(id).forEach(function (e) {
        if (e.kind !== 'CALLS' || kindOf(index, e.from) !== 'JAVA_METHOD' || seen.has(e.from)) return;
        seen.add(e.from);
        found(e.from);
        stack.push(e.from);
      });
      index.out(id).forEach(function (e) {
        if (e.kind !== 'OVERRIDES' || kindOf(index, e.to) !== 'JAVA_METHOD' || seen.has(e.to)) return;
        seen.add(e.to);
        found(e.to);
        stack.push(e.to);
      });
    }
  }

  /**
   * 표준 산출물의 표기로 적은 행 이름: 구문은 ID의 나머지, 메서드는 타입 전체 이름 + 단순 매개변수,
   * 엔드포인트는 `METHOD 경로`(resolvedPath가 있으면 `METHOD 치환경로 (원문)`, F-015-R37), 그 밖에는 ID의 나머지.
   */
  function exportLabel(index, rowKind, id) {
    var rest = G.idRemainder(id);
    if (rowKind === 'method') return G.methodLabel(rest, true);
    if (rowKind === 'endpoint') return G.endpointLabel(index.node(id), id);
    return rest;
  }

  /**
   * 대상 테이블을 정적으로 알 수 없는 구문(구문 ID 순).
   * [{ id, node, label, access(R, C, CU 등), location(구문의 file:line, 없으면 엣지의 위치), text(엣지의 attrs.text. 여럿이면 사전순으로 앞선 것) }]
   */
  function dynamicStatements(index) {
    var by = new Map();
    index.edges.forEach(function (e) {
      if (!isDynamicAccess(index, e)) return;
      var d = by.get(e.from);
      if (!d) by.set(e.from, d = { letters: {}, text: null, site: null });
      var letters = lettersOf(index, e);
      for (var i = 0; i < letters.length; i++) d.letters[letters.charAt(i)] = true;
      var text = e.attrs && e.attrs.text;
      if (text != null && String(text).trim() && (d.text == null || String(text) < d.text)) d.text = String(text);
      var site = G.lineLocation(e.location);
      if (site && (d.site == null || site < d.site)) d.site = site;
    });
    return Array.from(by.keys()).sort(G.compareText).map(function (id) {
      var d = by.get(id);
      var node = index.node(id) || null;
      return { id: id, node: node, label: index.label(id), access: format(d.letters), location: (node && G.lineLocation(node.location)) || d.site, text: d.text };
    });
  }

  /**
   * 매트릭스를 나눠서 계산하는 작업. 엔드포인트 기준은 엔드포인트마다 호출 사슬을 따라가므로 큰 그래프에서 오래 걸린다:
   * 화면은 step(budgetMs)를 여러 번 불러 사이사이 진행률(done/total)을 보이고, 끝나면 result()로 매트릭스를 받는다.
   * 구문·메서드·루틴 기준은 엣지를 한 번 훑으면 끝나므로 첫 step에서 끝난다.
   *
   * opts.rowKind: 'statement'(SQL 구문, 기본), 'method'(Java 메서드), 'endpoint'(엔드포인트), 'routine'(DB 루틴).
   * opts.maxDepth: 따라갈 최대 걸음 수(기본 20). 엔드포인트는 엔드포인트에서 구문까지의 걸음 수, 구문·메서드·루틴은 루틴 호출의 걸음 수다
   *   (구문→루틴, 메서드→루틴, 루틴→루틴이 한 걸음. 0이면 직접 접근만 센다).
   * opts.transitive: 메서드 기준일 때 호출을 거슬러 올라간 메서드도 행으로 넣는다(뷰어만 하는 선택 사항. 표준 산출물에는 없다).
   * opts.includeDataLoad: 데이터 적재 구문(dataLoad=true)도 넣는다. 기본은 뺀다(표준 산출물의 export.includeDataLoad=false와 같다).
   * 돌려주는 값: { total, done(), finished(), step(budgetMs) → 끝났으면 true, result() → 매트릭스(끝나기 전이면 그때까지의 것) }
   *   step의 budgetMs: 0이면 끝까지, 양수면 그 시간(ms)만큼, 음수면 엔드포인트 하나만 계산하고 돌아온다.
   */
  function createJob(index, opts) {
    var o = opts || {};
    var rowKind = rowKindOf(o.rowKind).key;
    var maxDepth = o.maxDepth == null ? DEFAULT_MAX_DEPTH : o.maxDepth;
    var includeDataLoad = o.includeDataLoad === true;
    var loads = dataLoadIds(index);
    var excluded = includeDataLoad ? null : loads;
    var byStatement = null;
    var bySubject = new Map();
    // 주체 → 확정 경로로 닿은 테이블(Set). 여기 없는 칸은 추론 경로로만 닿은 것이다(메서드·엔드포인트 기준에서만 본다).
    var confirmed = new Map();
    var marksInference = rowKind === 'method' || rowKind === 'endpoint';
    function confirm(subjectId, access) {
      if (!access) return;
      var set = confirmed.get(subjectId);
      if (!set) confirmed.set(subjectId, set = new Set());
      access.forEach(function (letters, table) { set.add(table); });
    }
    var endpoints = rowKind === 'endpoint' ? index.nodesOfKind('HTTP_ENDPOINT') : [];
    var walk = rowKind === 'endpoint' ? createWalk(includeDataLoad) : null;
    var at = 0;
    var finished = false;
    var built = null;

    function prepare() {
      if (!byStatement) byStatement = accessByStatement(index, excluded);
    }

    function runWhole() {
      if (rowKind === 'statement') {
        // 테이블에 직접 접근하는 구문과, 루틴을 부르는 구문(CALL, SQL 안의 함수 호출)
        var statements = new Set(byStatement.keys());
        index.edges.forEach(function (e) {
          if (e.kind !== 'CALLS' || (excluded && excluded.has(e.from))) return;
          if (kindOf(index, e.from) === 'SQL_STATEMENT' && kindOf(index, e.to) === 'DB_ROUTINE') statements.add(e.from);
        });
        statements.forEach(function (s) {
          merge(bySubject, s, byStatement.get(s), false);
          mergeRoutines(index, bySubject, s, calledRoutines(index, [s]), maxDepth - 1, null, byStatement);
        });
      } else if (rowKind === 'method') {
        index.edges.forEach(function (e) {
          if (e.kind !== 'EXECUTES' || kindOf(index, e.from) !== 'JAVA_METHOD') return;
          // 이 EXECUTES로 닿은 테이블만 따로 모아, 엣지가 확정이면 그 칸들을 확정으로 적는다.
          var reached = new Map();
          if (kindOf(index, e.to) === 'DB_ROUTINE') {
            mergeRoutines(index, reached, e.from, [e.to], maxDepth - 1, null, byStatement);
          } else {
            merge(reached, e.from, byStatement.get(e.to), false);
            mergeRoutines(index, reached, e.from, calledRoutines(index, [e.to]), maxDepth - 1, null, byStatement);
          }
          var access = reached.get(e.from);
          if (!access) return;
          var target = bySubject.get(e.from);
          if (!target) bySubject.set(e.from, target = new Map());
          access.forEach(function (letters, table) {
            var cell = target.get(table);
            if (!cell) target.set(table, cell = {});
            Object.keys(letters).forEach(function (c) { cell[c] = true; });
          });
          if ((e.confidence || 'RESOLVED') !== 'INFERRED') confirm(e.from, access);
        });
        if (o.transitive) {
          Array.from(bySubject.keys()).forEach(function (executor) {
            var own = bySubject.get(executor);
            walkUpMethods(index, executor, function (caller) {
              var target = bySubject.get(caller);
              if (!target) bySubject.set(caller, target = new Map());
              own.forEach(function (letters, table) {
                var cell = target.get(table);
                if (!cell) target.set(table, cell = {});
                Object.keys(letters).forEach(function (c) { cell[c] = true; });
              });
              // 호출을 거슬러 올린 행(뷰어만 하는 보기)에는 추론 표시를 하지 않는다: 어느 호출이 추론인지 따지지 않고 합친 칸이다.
              confirm(caller, own);
            });
          });
        }
      } else if (rowKind === 'routine') {
        index.nodesOfKind('DB_ROUTINE').forEach(function (routine) {
          var own = statementsOf(index, routine.id);
          own.forEach(function (s) { merge(bySubject, routine.id, byStatement.get(s), false); });
          mergeRoutines(index, bySubject, routine.id, calledRoutines(index, own.concat([routine.id])), maxDepth - 1, [routine.id], byStatement);
        });
      }
    }

    function step(budgetMs) {
      if (finished) return true;
      prepare();
      if (rowKind !== 'endpoint') {
        runWhole();
        finished = true;
        return true;
      }
      // budgetMs: 0이면 끝까지, 양수면 그 시간(ms)만큼, 음수면 엔드포인트 하나만 계산하고 돌아온다.
      var deadline = budgetMs > 0 ? Date.now() + budgetMs : 0;
      while (at < endpoints.length) {
        var endpoint = endpoints[at++];
        reachable(index, endpoint.id, maxDepth, walk).forEach(function (inferredOnly, reached) {
          var access = byStatement.get(reached);
          merge(bySubject, endpoint.id, access, inRoutine(index, reached));
          if (!inferredOnly) confirm(endpoint.id, access);
        });
        if (budgetMs < 0 || (deadline && Date.now() >= deadline)) break;
      }
      finished = at >= endpoints.length;
      // 끝나면 자식 계산 결과를 놓아 준다(큰 그래프에서 수십만 개가 쌓인다).
      if (finished) walk.cache = new Map();
      return finished;
    }

    function result() {
      // 끝난 뒤에 만든 것만 다시 쓴다(끝나기 전에 만든 것은 그때까지의 부분 결과다).
      if (built && !built.partial) return built;
      var tableIds = new Set();
      var rows = [];
      var dynamicColumn = false;
      bySubject.forEach(function (access, id) {
        if (!access.size) return;
        var node = index.node(id) || null;
        var cells = {};
        var sure = confirmed.get(id);
        access.forEach(function (letters, table) {
          cells[table] = format(letters) + (marksInference && !(sure && sure.has(table)) ? INFERRED_MARK : '');
          if (table === DYNAMIC) dynamicColumn = true;
          else tableIds.add(table);
        });
        var exported = exportLabel(index, rowKind, id);
        rows.push({ id: id, node: node, label: rowKind === 'endpoint' ? exported : index.label(id), exportLabel: exported,
          location: node ? G.lineLocation(node.location) : null, cells: cells });
      });
      rows.sort(function (a, b) { return G.compareText(a.id, b.id); });
      var tables = Array.from(tableIds).map(function (id) {
        return { id: id, name: G.idRemainder(id), node: index.node(id) || null };
      });
      tables.sort(function (a, b) { return G.compareText(a.name, b.name) || G.compareText(a.id, b.id); });
      built = {
        rowKind: rowKind, tables: tables, rows: rows, dynamicColumn: dynamicColumn,
        dynamicStatements: rowKind === 'statement' ? dynamicStatements(index) : [],
        // 데이터 적재 구문: 그래프에 있는 수와, 이 매트릭스에서 뺀 수(넣었으면 0)
        dataLoads: loads.size, dataLoadExcluded: includeDataLoad ? 0 : loads.size,
        partial: !finished
      };
      return built;
    }

    return {
      rowKind: rowKind,
      total: rowKind === 'endpoint' ? endpoints.length : 1,
      done: function () { return rowKind === 'endpoint' ? at : finished ? 1 : 0; },
      finished: function () { return finished; },
      step: step,
      result: result
    };
  }

  /**
   * 매트릭스를 한 번에 만든다(옵션은 createJob과 같다).
   * 결과: { rowKind, tables: [{id, name, node}], rows: [{id, node, label, exportLabel, location, cells: {테이블 ID 또는 DYNAMIC: 글자}}],
   *        dynamicColumn(동적 구문의 접근이 있어 (동적) 열이 나오는지), dynamicStatements(구문 기준에서만 채운다),
   *        dataLoads(그래프의 데이터 적재 구문 수), dataLoadExcluded(이 매트릭스에서 뺀 수) }
   * 행은 노드 ID 순, 테이블은 이름 순. 접근이 하나도 없는 주체와 테이블은 넣지 않는다.
   */
  function buildMatrix(index, opts) {
    var job = createJob(index, opts);
    job.step(0);
    return job.result();
  }

  /** 화면과 CSV의 열: 테이블(이름 순)과, 동적 구문의 접근이 있으면 그 뒤의 (동적). */
  function columnsOf(matrix) {
    var columns = matrix.tables.slice();
    if (matrix.dynamicColumn) columns.push({ id: DYNAMIC, name: DYNAMIC, node: null, dynamic: true });
    return columns;
  }

  function tokensOf(text) {
    var t = String(text || '').trim().toLowerCase();
    return t ? t.split(/\s+/) : [];
  }

  function matches(text, tokens) {
    var lower = text.toLowerCase();
    for (var i = 0; i < tokens.length; i++) if (lower.indexOf(tokens[i]) < 0) return false;
    return true;
  }

  /** filter.rowText, filter.tableText로 좁힌다. 남은 행에 접근이 없는 테이블과, 남은 테이블에 접근이 없는 행은 뺀다. (동적) 열도 테이블처럼 거른다. */
  function filterMatrix(matrix, filter) {
    var f = filter || {};
    var rowTokens = tokensOf(f.rowText);
    var tableTokens = tokensOf(f.tableText);
    var tables = matrix.tables;
    var rows = matrix.rows;
    var dynamicColumn = !!matrix.dynamicColumn;
    if (rowTokens.length) rows = rows.filter(function (r) { return matches(r.label, rowTokens); });
    if (tableTokens.length) {
      tables = tables.filter(function (t) { return matches(t.name, tableTokens); });
      dynamicColumn = dynamicColumn && matches(DYNAMIC, tableTokens);
      rows = rows.filter(function (r) { return (dynamicColumn && r.cells[DYNAMIC]) || tables.some(function (t) { return r.cells[t.id]; }); });
    }
    if (rowTokens.length || tableTokens.length) {
      var touched = new Set();
      rows.forEach(function (r) { Object.keys(r.cells).forEach(function (id) { touched.add(id); }); });
      tables = tables.filter(function (t) { return touched.has(t.id); });
      dynamicColumn = dynamicColumn && touched.has(DYNAMIC);
    }
    return { rowKind: matrix.rowKind, tables: tables, rows: rows, dynamicColumn: dynamicColumn, dynamicStatements: matrix.dynamicStatements || [],
      dataLoads: matrix.dataLoads || 0, dataLoadExcluded: matrix.dataLoadExcluded || 0 };
  }

  /** CSV 칸(RFC 4180). 쉼표, 따옴표, 줄바꿈이 있으면 큰따옴표로 감싸고 따옴표는 두 번 쓴다. */
  function csvCell(text) {
    var s = text == null ? '' : String(text);
    return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  }

  /**
   * 표준 산출물(`crud/table-*.csv`)과 같은 모양의 CSV: 머리줄 `<기준>,위치,<테이블…>[,(동적)]`, 줄 끝 `\n`.
   * opts.bom이 참이면 UTF-8 BOM을 앞에 붙인다(엑셀이 한글을 깨뜨리지 않게).
   */
  function toCsv(matrix, opts) {
    var columns = columnsOf(matrix);
    var lines = [[rowKindOf(matrix.rowKind).csvTitle, '위치'].concat(columns.map(function (c) { return c.name; }))];
    matrix.rows.forEach(function (r) {
      lines.push([r.exportLabel == null ? r.label : r.exportLabel, r.location || ''].concat(columns.map(function (c) { return r.cells[c.id] || ''; })));
    });
    return (opts && opts.bom ? '﻿' : '') + lines.map(function (line) { return line.map(csvCell).join(','); }).join('\n') + '\n';
  }

  var api = {
    DYNAMIC: DYNAMIC, INFERRED_MARK: INFERRED_MARK, DEFAULT_MAX_DEPTH: DEFAULT_MAX_DEPTH, ROW_KINDS: ROW_KINDS, LEGEND: LEGEND,
    buildMatrix: buildMatrix, createJob: createJob, columnsOf: columnsOf, filterMatrix: filterMatrix, dynamicStatements: dynamicStatements, toCsv: toCsv
  };
  CA.crud = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
