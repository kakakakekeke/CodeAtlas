// 콜트리 전개. 화면에서 테이블까지 이어지는 엣지를 따라 양방향 트리를 지연 계산한다. DOM을 쓰지 않는다.
// 따라가는 규칙은 표준 산출물(codeatlas-export의 FlowNavigator, F-015)과 같다: 구현 전개와 주입 좁히기, 트리거의 사건 대조, 스텝의 구성 요소,
// 수신자 문맥으로 팬아웃 엣지(상속 EXECUTES, 리플렉션 CALLS) 좁히기, 멀티모듈 엔드포인트의 핸들러 고르기, 자식 순서(호출 지점 순),
// 접근자·소스 없는 메서드 접기, 데이터 적재 구문 빼기. 계약의 "소비자가 지키는 규칙"(F-001 design 3.4)을 JS로 옮긴 것이다.
// 뷰어만 더 하는 것: 뷰 → 정의 쿼리, DB 패키지 → 루틴, 컬럼 줄 보기, 호출 지점 수, 같은 노드로 가는 줄을 관계 이름별로 따로 보이기.
(function (root) {
  'use strict';
  if (typeof module === 'object' && module.exports) require('./graph.js');
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var G = CA.graph;

  /** 콜트리의 기준으로 고를 수 있는 노드 종류. */
  var ROOT_KINDS = ['JAVA_METHOD', 'HTTP_ENDPOINT', 'HTTP_REQUEST', 'JSP_PAGE', 'JS_MODULE', 'JS_FUNCTION',
    'SQL_STATEMENT', 'SQL_FRAGMENT', 'DB_TABLE', 'DB_VIEW', 'DB_COLUMN', 'DB_ROUTINE', 'DB_PACKAGE', 'DB_SEQUENCE', 'BATCH_JOB', 'BATCH_STEP'];

  // from → to 방향이 "호출되는 쪽"인 엣지.
  var FLOW_KINDS = { REQUESTS: 1, MATCHES: 1, ROUTES_TO: 1, CALLS: 1, EXECUTES: 1, READS: 1, WRITES: 1, RENDERS: 1, INCLUDES: 1, USES: 1 };
  // CONTAINS는 실행 흐름을 뜻하는 쌍만 따른다(계약 design 3.4의 "CONTAINS의 조합").
  //  flow: 양쪽으로 따른다. 포함된 쪽이 실행되는 길이 이것뿐이다(루틴 → 구문, 뷰 → 정의 쿼리, 잡 → 스텝, JSP → 인라인 스크립트).
  //  weak: 아래로는 따르고, 위로는 다른 호출자가 없을 때만 따른다(모듈·함수 → 함수. 호출 엣지가 있으면 그쪽이 진짜 경로다).
  //  down: 아래로만 따른다(패키지 → 루틴. 패키지가 루틴을 호출하는 것은 아니다).
  //  entry: 스텝이 타입(또는 빈)만 가리킬 때 그 타입의 public 메서드로 펼친다. 위로는 그런 타입일 때만 따른다.
  var CONTAINS_PAIRS = {
    'DB_ROUTINE>SQL_STATEMENT': 'flow', 'DB_VIEW>SQL_STATEMENT': 'flow', 'BATCH_JOB>BATCH_STEP': 'flow', 'JSP_PAGE>JS_MODULE': 'flow',
    'JS_MODULE>JS_FUNCTION': 'weak', 'JS_FUNCTION>JS_FUNCTION': 'weak',
    'DB_PACKAGE>DB_ROUTINE': 'down',
    'JAVA_TYPE>JAVA_METHOD': 'entry'
  };
  // 관계 이름에 적어 줄 호출 방식. 보통의 호출(virtual, interface, static, call 등)은 적지 않는다.
  var NOTABLE_CALL_TYPES = { redirect: 1, forward: 1, 'super': 1, ref: 1, jsx: 1, methodRef: 1, reflection: 1, modelAttribute: 1 };
  // 표준 산출물이 줄의 세부 표시(괄호)나 표식으로 적는 호출 방식(FlowNavigator.callDetail). reflection은 [reflect], modelAttribute는 [before]다.
  var DETAIL_CALL_TYPES = { redirect: 1, forward: 1, 'super': 1, reflection: 1, modelAttribute: 1 };
  var CALL_REFLECTION = 'reflection';
  var CALL_MODEL_ATTRIBUTE = 'modelAttribute';
  // 걸음을 따로 세우는 호출 방식: 같은 대상이라도 보통의 호출과 한 줄로 합치지 않는다(FlowNavigator.Step.callMode).
  var SEPARATE_CALL_MODES = { 'super': 1, modelAttribute: 1, redirect: 1, forward: 1 };
  var DETAIL_LAYOUT = 'layout';
  var DETAIL_LOAD_UNVERIFIED = '로드 미확인';
  // 추론 걸음의 까닭(표준 산출물 FlowNavigator의 INFERENCE_* 문구와 같다). CRUD 매트릭스 칸의 `?`가 이것으로 정해진다(F-015-R52).
  var INFERENCE_RECEIVER_UNCERTAIN = '수신자 타입에 맞는 대상을 고르지 못해 전부 따라감([?])';
  var INFERENCE_RECEIVER_UNKNOWN = '수신자 타입으로 대상 하나를 정하지 못함(상속 매퍼 메서드의 구문·리플렉션 호출)';
  var INFERENCE_TRIGGER_UNCERTAIN = '그 쓰기에 일어나는지 알 수 없는 트리거([trigger?])';
  // 수신자 문맥을 끊는 호출 방식: 수신 객체가 없거나 새로 만든 객체다.
  var CONTEXT_BREAKING = { 'static': 1, constructor: 1, methodRef: 1 };
  // 엣지 종류의 선언 순서(계약의 EdgeKind). 같은 노드로 가는 걸음을 하나로 볼 때 앞선 종류가 그 걸음의 종류다(WRITES는 늘 이긴다).
  var KIND_RANK = {};
  ['CONTAINS', 'EXTENDS', 'IMPLEMENTS', 'ASSOCIATES', 'DEPENDS_ON', 'OVERRIDES', 'CALLS', 'INSTANTIATES', 'ACCESSES', 'ANNOTATED_BY', 'DEFINES_BEAN',
    'INJECTS', 'ROUTES_TO', 'RENDERS', 'REQUESTS', 'MATCHES', 'IMPORTS', 'INCLUDES', 'EXECUTES', 'USES_TYPE', 'READS', 'WRITES', 'JOINS', 'REFERENCES',
    'ALIAS_OF', 'USES', 'NEXT', 'TRIGGERS_ON'].forEach(function (k, i) { KIND_RANK[k] = i; });
  // 호출 지점의 위치순으로 놓는 걸음: 한 파일 안에서 순서가 있는 엣지.
  var POSITIONED_KINDS = { CALLS: 1, EXECUTES: 1, REQUESTS: 1, INCLUDES: 1, RENDERS: 1, USES: 1 };
  // 필터·인터셉터의 진입 메서드 이름: 컨테이너가 요청마다 부르는 것만(초기화·종료는 뺀다. F-015-R39).
  var FILTER_ENTRY_METHODS = { doFilter: 1, doFilterInternal: 1, preHandle: 1, postHandle: 1, afterCompletion: 1, afterConcurrentHandlingStarted: 1 };
  var CONFIDENCE_RANK = { RESOLVED: 0, INFERRED: 1, UNRESOLVED: 2 };
  var METHOD_PREFIX = 'java:method:';
  var TYPE_PREFIX = 'java:type:';
  var MAX_HIERARCHY_DEPTH = 30;
  var MAX_SENSITIVE_DEPTH = 60;
  var FIRES_NO = 0, FIRES_YES = 1, FIRES_UNCERTAIN = 2;
  var EMPTY = Object.freeze([]);

  /** 색인에 붙여 두는 계산 결과(타입 계층, 재정의, 판정). 색인은 바뀌지 않으므로 한 번 계산한 것은 다시 계산하지 않는다. */
  function cacheOf(index) {
    return index._calltree || (index._calltree = {
      owner: new Map(), dispatch: new Map(), abstract: new Map(), overriders: new Map(), supertypes: new Map(), subtypes: new Map(),
      bound: new Map(), fanOut: new Map(), sensitive: new Map(), accessor: new Map(), external: new Map(), module: new Map(), before: new Map()
    });
  }

  function kindOf(index, id) {
    var node = index.node(id);
    return node ? node.kind : G.kindOfId(id) || 'UNRESOLVED';
  }

  function listAttr(holder, key) {
    var v = holder && holder.attrs ? holder.attrs[key] : null;
    return Array.isArray(v) ? v : typeof v === 'string' ? [v] : EMPTY;
  }

  function outOf(index, id, kind) {
    return index.outOf ? index.outOf(id, kind) : index.out(id).filter(function (e) { return e.kind === kind; });
  }

  function inOf(index, id, kind) {
    return index.inOf ? index.inOf(id, kind) : index.in(id).filter(function (e) { return e.kind === kind; });
  }

  function containsMode(index, e) {
    var from = index.node(e.from);
    var to = index.node(e.to);
    return (from && to && CONTAINS_PAIRS[from.kind + '>' + to.kind]) || null;
  }

  /** 호출 줄의 세부 표시(표준 산출물의 괄호·표식): redirect, forward, super, reflection, modelAttribute, 로드 미확인. 없으면 null. */
  function callDetail(e) {
    var callType = e.attrs ? e.attrs.callType : null;
    if (e.attrs && e.attrs.loadUnverified === true) return DETAIL_LOAD_UNVERIFIED;
    return callType != null && Object.prototype.hasOwnProperty.call(DETAIL_CALL_TYPES, callType) ? callType : null;
  }

  function linkLabel(e, callees) {
    var a = e.attrs || {};
    if (e.kind === 'OVERRIDES') return '구현';
    if (e.kind === 'TRIGGERS_ON') return callees ? '트리거' : '트리거 대상';
    if (e.kind === 'DEFINES_BEAN') return callees ? '빈 정의' : '빈';
    if (e.kind === 'WRITES' && a.op) return 'WRITES(' + a.op + ')';
    if ((e.kind === 'USES' || e.kind === 'EXECUTES') && a.role) return e.kind + '(' + a.role + ')';
    if (e.kind === 'CALLS' && Object.prototype.hasOwnProperty.call(NOTABLE_CALL_TYPES, a.callType)) return 'CALLS(' + a.callType + ')';
    return e.kind;
  }

  // ---------- 요청의 경계 (F-015-R41, R42, R47, R48)

  /**
   * 걸음이 추론이면 그 까닭, 확정이면 null(트리거의 불확실은 줄을 만들 때 따로 본다). 표준 산출물의 FlowNavigator.inferenceOf와 같은 규칙이다:
   * 콜트리에서 `~`(신뢰도 INFERRED)·`[?]`가 붙는 걸음이 추론이되, 프레임워크의 규칙으로 대상이 정해지는 세 가지 — 요청 전 처리
   * (callType=modelAttribute), forward(같은 요청 안의 이동), 수신자 문맥이 대상 하나를 고른 팬아웃 엣지(pick='selected') — 는 확정으로 본다. 팬아웃 엣지를 문맥 없이
   * 전부 따라갔거나 문맥이 대상 여럿과 맞았으면(pick='unselected') 표시가 없어도 추론이다.
   */
  function inferenceOf(e, detail, confidence, receiverUncertain, pick, impl) {
    if (receiverUncertain) return INFERENCE_RECEIVER_UNCERTAIN;
    if (pick === 'unselected') return INFERENCE_RECEIVER_UNKNOWN;
    var a = (e && e.attrs) || {};
    var ambiguous = a.ambiguous === true;
    if (pick === 'selected') return ambiguous ? '후보 여럿(ambiguous)' : null;
    if (e.kind === 'CALLS' && detail === CALL_MODEL_ATTRIBUTE) return null;
    if (confidence !== 'INFERRED') return null;
    if (impl) return '추론한 재정의(OVERRIDES)';
    if (ambiguous) return '후보 여럿(ambiguous)';
    if (a.prefixMatch === true) return '구문 ID 접두 일치(prefixMatch)';
    if (a.viaArgument === true) return '인자로 넘긴 구문 ID(viaArgument)';
    if (e.kind === 'RENDERS' && a.dynamicSegment === true) {
      return a.narrowedBy === 'property' ? '동적 조각이 든 뷰 이름을 프로퍼티 값으로 정함(narrowedBy=property)'
        : a.narrowedBy === 'moduleName' ? '동적 조각이 든 뷰 이름을 모듈 이름으로 정함(narrowedBy=moduleName)' : '동적 조각이 든 뷰 이름의 후보(dynamicSegment)';
    }
    if (e.kind === 'INCLUDES' && a.dynamicDecorator === true) return '동적 데코레이터 이름(dynamicDecorator)';
    if (a.candidate === true) return '뷰 후보(candidate)';
    if (a.suffixMatch === true) return '뷰 이름 접미 일치(suffixMatch)';
    if (detail === DETAIL_LOAD_UNVERIFIED) return '스크립트 로드 미확인(loadUnverified)';
    if (a.crossModule === true) return '다른 모듈의 핸들러(crossModule)';
    if (e.kind === 'CALLS') {
      // forward는 같은 요청 안의 서버 내부 이동이라 엣지가 INFERRED여도 확정 경로로 본다(대상 매칭이 ambiguous·suffixMatch·crossModule이면 위에서 추론).
      if (a.callType === 'forward') return null;
      if (a.callType === 'redirect') return '뷰 이름·경로로 이은 redirect';
      if (a.callType === CALL_REFLECTION) return '리플렉션 호출(reflection)';
      if (a.inheritedFrom != null) return 'classpath에 없는 상위 타입의 메서드(inheritedFrom)';
      if (a.resolution === 'global') return '다른 스크립트의 전역 함수(resolution=global)';
      if (a.viaProp != null) return 'props로 받은 함수(viaProp)';
    }
    return '추론한 관계(' + e.kind + ')';
  }

  function isRedirectLink(link) { return !!link && link.kind === 'CALLS' && link.detail === 'redirect'; }
  function isForwardLink(link) { return !!link && link.kind === 'CALLS' && link.detail === 'forward'; }
  function isBeforeLink(link) { return !!link && link.kind === 'CALLS' && link.detail === CALL_MODEL_ATTRIBUTE; }
  /** 레이아웃으로 간 줄: 내용 페이지 → 데코레이터, tiles template → 조각(INCLUDES), 핸들러 → tiles template(RENDERS). */
  function isLayoutLink(link) { return !!link && (link.kind === 'INCLUDES' || link.kind === 'RENDERS') && link.detail === DETAIL_LAYOUT; }

  /**
   * via로 도착한 메서드가 "요청으로 진입한 핸들러"인지: 기준 노드이거나, 엔드포인트에서 ROUTES_TO로 도착했거나, 다른 핸들러의
   * redirect·forward로 도착했다. 이때만 callType=modelAttribute 엣지([before])를 따른다.
   */
  function requestEntry(via) {
    return !via || via.kind === 'ROUTES_TO' || isRedirectLink(via) || isForwardLink(via);
  }

  /** methodId에서 callType=modelAttribute 호출이 나가는지(요청 전 처리가 있는 핸들러). */
  function hasBeforeCalls(index, methodId) {
    if (String(methodId).indexOf('java:method:') !== 0) return false;
    var cache = cacheOf(index).before;
    var cached = cache.get(methodId);
    if (cached !== undefined) return cached;
    var found = outOf(index, methodId, 'CALLS').some(function (e) { return !!e.attrs && e.attrs.callType === CALL_MODEL_ATTRIBUTE; });
    cache.set(methodId, found);
    return found;
  }

  /** 클라이언트 라우트인지: 서버 요청이 아니라 화면 안의 이동을 적은 HTTP_REQUEST(clientRoute=true). */
  function isClientRoute(index, id) {
    var node = index.node(id);
    return !!node && node.kind === 'HTTP_REQUEST' && node.attrs.clientRoute === true;
  }

  function unresolvedTarget(index, e) {
    var target = index.node(e.to);
    return e.confidence === 'UNRESOLVED' || !target || target.kind === 'UNRESOLVED';
  }

  // 실행·뷰 줄에 덧붙이는 표시(표준 산출물의 FlowNavigator.relationDetail과 같다). 모두 추론 엣지다.
  var DETAIL_PREFIX_MATCH = '접두 일치';
  var DETAIL_VIA_ARGUMENT = '인자 구문';
  var DETAIL_DYNAMIC_VIEW = '동적 뷰 이름 후보';
  // 링커가 후보를 좁힌 근거(RENDERS의 narrowedBy): "후보"라 부르지 않고 그 근거를 적는다(F-015-R75).
  var DETAIL_DYNAMIC_VIEW_BY_PROPERTY = '동적 뷰 이름: 프로퍼티 값으로 정함';
  var DETAIL_DYNAMIC_VIEW_BY_MODULE = '동적 뷰 이름: 모듈 이름으로 정함';

  /**
   * 접두만 맞은 구문(prefixMatch), 인자로 넘긴 구문 ID(viaArgument), 동적 조각이 든 뷰 이름의 후보(RENDERS의 dynamicSegment. 좁힌 것은 narrowedBy에 따라
   * 프로퍼티 값·모듈 이름으로 정함). 없으면 null.
   */
  function relationDetail(e) {
    var a = e.attrs || {};
    if (e.kind === 'EXECUTES') return a.viaArgument === true ? DETAIL_VIA_ARGUMENT : a.prefixMatch === true ? DETAIL_PREFIX_MATCH : null;
    if (e.kind === 'RENDERS') {
      if (a.dynamicSegment !== true) return null;
      return a.narrowedBy === 'property' ? DETAIL_DYNAMIC_VIEW_BY_PROPERTY : a.narrowedBy === 'moduleName' ? DETAIL_DYNAMIC_VIEW_BY_MODULE : DETAIL_DYNAMIC_VIEW;
    }
    return null;
  }

  function sameStart(a, b) {
    if (!a || !b) return !a && !b;
    return (a.file || null) === (b.file || null) && (a.startLine || 0) === (b.startLine || 0) && (a.startColumn || 0) === (b.startColumn || 0);
  }

  /**
   * 미해결 EXECUTES가 같은 지점에서 다른 방법으로 풀렸는지: 같은 출발 노드에서 같은 위치로 나가는 EXECUTES 가운데 resolvedFrom이
   * 이 엣지의 미해결 대상인 것이 있다(링커가 접두 일치 등으로 후보 구문에 이은 것). 그런 미해결 줄은 따로 내지 않는다(F-015-R63).
   */
  function resolvedAnotherWay(index, fromId, e) {
    if (e.kind !== 'EXECUTES' || !unresolvedTarget(index, e)) return false;
    var out = index.out(fromId);
    for (var i = 0; i < out.length; i++) {
      var o = out[i];
      if (o.kind === 'EXECUTES' && o.attrs && o.attrs.resolvedFrom === e.to && sameStart(o.location, e.location)) return true;
    }
    return false;
  }

  /** 레이아웃 관계인 INCLUDES인지: JSP → JSP이고 via가 sitemesh(내용 페이지 → 데코레이터)나 tiles(template → 조각). */
  function layoutInclude(index, e) {
    var via = e.attrs ? e.attrs.via : null;
    return e.kind === 'INCLUDES' && (via === 'sitemesh' || via === 'tiles') && kindOf(index, e.from) === 'JSP_PAGE' && kindOf(index, e.to) === 'JSP_PAGE';
  }

  /** 이 JSP를 레이아웃으로 쓰는 화면 수. */
  function layoutUsers(index, jspId) {
    var users = new Set();
    inOf(index, jspId, 'INCLUDES').forEach(function (e) { if (layoutInclude(index, e)) users.add(e.from); });
    return users.size;
  }

  function isTableKind(kind) {
    return kind === 'DB_TABLE' || kind === 'DB_VIEW';
  }

  function isColumnId(index, id) {
    var node = index.node(id);
    return node ? node.kind === 'DB_COLUMN' : String(id).indexOf('db:column:') === 0;
  }

  /** READS/WRITES 엣지의 대상을 테이블로 접는다. 대상이 테이블·뷰면 그대로, 컬럼이면 소속 테이블, 그 밖이면 null. */
  function accessedTable(index, e) {
    var target = index.node(e.to);
    if (target && isTableKind(target.kind)) return e.to;
    if (target && target.kind === 'DB_COLUMN') return index.tableOfColumn(e.to);
    return null;
  }

  // ---------- 트리거: 쓰기의 종류(op)와 트리거가 걸린 사건(triggerEvent)이 맞을 때만 이어진다 (F-015-R18).

  function endsColumnList(token) {
    var t = token.toUpperCase();
    return t === 'OR' || t === 'ON' || t === 'FOR' || t === 'REFERENCING' || t === 'WHEN';
  }

  /**
   * 트리거의 triggerEvent("AFTER UPDATE OF ORDER_STATUS", "BEFORE INSERT OR DELETE")를 사건별로 나눈다.
   * 시점(BEFORE, AFTER, INSTEAD OF, NO CASCADE)은 읽고 버린다. 결과: [{ event: INSERT|UPDATE|DELETE, columns: [UPDATE OF 뒤의 컬럼] }]
   * 사건을 하나도 알아보지 못하면 빈 배열이다(어떤 쓰기에 일어나는지 알 수 없는 트리거).
   */
  function parseTriggerEvent(text) {
    var clauses = [];
    if (text == null || !String(text).trim()) return clauses;
    var tokens = String(text).replace(/,/g, ' ').trim().split(/\s+/).filter(Boolean);
    for (var i = 0; i < tokens.length; i++) {
      var event = tokens[i].toUpperCase();
      if (event !== 'INSERT' && event !== 'UPDATE' && event !== 'DELETE') continue;
      var columns = [];
      if (event === 'UPDATE' && i + 1 < tokens.length && tokens[i + 1].toUpperCase() === 'OF') {
        var k = i + 2;
        for (; k < tokens.length && !endsColumnList(tokens[k]); k++) {
          var t = tokens[k];
          // 따옴표로 감싼 식별자는 원문 그대로, 아니면 대문자(그래프의 DB 이름 규칙과 같다).
          var name = t.length >= 2 && t.charAt(0) === '"' && t.charAt(t.length - 1) === '"' ? t.substring(1, t.length - 1) : t.toUpperCase();
          if (columns.indexOf(name) < 0) columns.push(name);
        }
        i = k - 1;
      }
      clauses.push({ event: event, columns: columns });
    }
    return clauses;
  }

  /** WRITES의 종류: attrs.op, 없으면 구문의 statementType. INSERT, UPDATE, DELETE, MERGE 가운데 하나이거나 null. */
  function writeOp(index, e, statementId) {
    var normalize = function (op) {
      if (op == null) return null;
      var upper = String(op).trim().toUpperCase();
      return upper === 'INSERT' || upper === 'UPDATE' || upper === 'DELETE' || upper === 'MERGE' ? upper : null;
    };
    var op = normalize(e.attrs && e.attrs.op);
    if (op) return op;
    var stmt = index.node(statementId);
    return normalize(stmt && stmt.attrs.statementType);
  }

  function columnName(columnId, tableId) {
    var rest = G.idRemainder(columnId);
    var prefix = G.idRemainder(tableId) + '.';
    return rest.indexOf(prefix) === 0 ? rest.substring(prefix.length) : rest.substring(rest.lastIndexOf('.') + 1);
  }

  /**
   * statementId가 tableId에 하는 쓰기가 triggerId를 일으키는지.
   * UPDATE OF <컬럼> 트리거는 구문의 WRITES 컬럼 엣지가 그 컬럼을 포함할 때만 맞다. MERGE는 INSERT와 UPDATE 둘 다다.
   * truncate=true인 쓰기는 DML 트리거를 일으키지 않는다. 알 수 없으면(사건을 모르는 트리거, 컬럼 지정 트리거인데 구문이 쓰는 컬럼을
   * 모름, 쓰기의 종류를 모름) FIRES_UNCERTAIN이다.
   */
  function fires(index, triggerId, statementId, tableId) {
    var ops = {};
    var updatedColumns = [];
    var unknownOp = false;
    var anyWrite = false;
    var out = outOf(index, statementId, 'WRITES');
    for (var i = 0; i < out.length; i++) {
      var e = out[i];
      var column = isColumnId(index, e.to);
      var target = column ? index.tableOfColumn(e.to) : e.to;
      if (target !== tableId || (e.attrs && e.attrs.truncate === true)) continue;
      anyWrite = true;
      var op = writeOp(index, e, statementId);
      if (!op) { unknownOp = true; continue; }
      ops[op] = true;
      if (column && (op === 'UPDATE' || op === 'MERGE')) updatedColumns.push(columnName(e.to, tableId).toUpperCase());
    }
    if (!anyWrite) return FIRES_NO;
    var trigger = index.node(triggerId);
    var clauses = parseTriggerEvent(trigger && trigger.attrs.triggerEvent);
    if (!clauses.length) return FIRES_UNCERTAIN;
    var inserts = !!(ops.INSERT || ops.MERGE);
    var updates = !!(ops.UPDATE || ops.MERGE);
    var deletes = !!ops.DELETE;
    var uncertain = unknownOp;
    for (var c = 0; c < clauses.length; c++) {
      var clause = clauses[c];
      if (clause.event === 'INSERT') { if (inserts) return FIRES_YES; }
      else if (clause.event === 'DELETE') { if (deletes) return FIRES_YES; }
      else {
        var covers = !clause.columns.length || clause.columns.some(function (name) { return updatedColumns.indexOf(name.toUpperCase()) >= 0; });
        if (updates && covers) return FIRES_YES;
        // 컬럼 지정 트리거인데 구문이 어느 컬럼을 쓰는지 모른다.
        if (updates && !updatedColumns.length) uncertain = true;
      }
    }
    return uncertain ? FIRES_UNCERTAIN : FIRES_NO;
  }

  // ---------- 타입 계층과 인터페이스 디스패치 (F-015-R3, R19, R34)

  function isJavaMethodId(id) {
    return String(id).indexOf(METHOD_PREFIX) === 0;
  }

  /** 바이너리 이름 또는 타입 ID를 타입 ID로. 비어 있으면 null. */
  function typeId(nameOrId) {
    if (nameOrId == null) return null;
    var text = String(nameOrId).trim();
    if (!text) return null;
    return text.indexOf(TYPE_PREFIX) === 0 ? text : TYPE_PREFIX + text;
  }

  /** 메서드를 가진 타입의 ID. CONTAINS를 먼저 보고 없으면 메서드 ID에서 만든다. */
  function ownerType(index, methodId) {
    var cache = cacheOf(index).owner;
    var cached = cache.get(methodId);
    if (cached !== undefined) return cached;
    var owner = null;
    var incoming = inOf(index, methodId, 'CONTAINS');
    for (var i = 0; i < incoming.length && !owner; i++) {
      if (kindOf(index, incoming[i].from) === 'JAVA_TYPE') owner = incoming[i].from;
    }
    if (!owner) {
      var rest = G.idRemainder(methodId);
      var hash = rest.indexOf('#');
      owner = TYPE_PREFIX + (hash < 0 ? rest : rest.substring(0, hash));
    }
    cache.set(methodId, owner);
    return owner;
  }

  function typeKindOf(index, id) {
    var t = index.node(id);
    return t ? t.attrs.typeKind : null;
  }

  /** 이 메서드가 호출될 때 구현으로 디스패치될 수 있는지(추상 메서드이거나 인터페이스의 인스턴스 메서드). */
  function isDispatchTarget(index, methodId) {
    var cache = cacheOf(index).dispatch;
    var cached = cache.get(methodId);
    if (cached !== undefined) return cached;
    var result = false;
    var method = index.node(methodId);
    if (method && method.kind === 'JAVA_METHOD') {
      var modifiers = listAttr(method, 'modifiers');
      if (modifiers.indexOf('abstract') >= 0) result = true;
      else if (modifiers.indexOf('static') >= 0) result = false;
      else if (typeKindOf(index, ownerType(index, methodId)) === 'interface') result = true;
      else {
        var incoming = inOf(index, methodId, 'CALLS');
        for (var i = 0; i < incoming.length && !result; i++) {
          if (incoming[i].attrs && incoming[i].attrs.callType === 'interface') result = true;
        }
      }
    }
    cache.set(methodId, result);
    return result;
  }

  /** 본문이 없는 메서드인지: abstract이거나, 인터페이스의 default·static·private이 아닌 메서드. */
  function isAbstract(index, methodId) {
    var cache = cacheOf(index).abstract;
    var cached = cache.get(methodId);
    if (cached !== undefined) return cached;
    var result = false;
    var method = index.node(methodId);
    if (method) {
      var modifiers = listAttr(method, 'modifiers');
      if (modifiers.indexOf('abstract') >= 0) result = true;
      else if (modifiers.indexOf('default') >= 0 || modifiers.indexOf('static') >= 0 || modifiers.indexOf('private') >= 0) result = false;
      else result = typeKindOf(index, ownerType(index, methodId)) === 'interface';
    }
    cache.set(methodId, result);
    return result;
  }

  /**
   * methodId를 직접·간접으로 재정의(구현)한 메서드 가운데 본문이 있는 것(ID 순). OVERRIDES는 가장 가까운 상위 하나로만 가므로
   * 거꾸로 전이적으로 따라간다. 본문이 없는 것(추상 메서드)은 거쳐 가기만 한다.
   * 결과: [{ id, confidence(그 구현까지 온 OVERRIDES 사슬에 RESOLVED가 아닌 엣지가 있으면 INFERRED), edge(그 메서드에서 나가는 OVERRIDES 엣지) }]
   */
  function overriders(index, methodId) {
    var cache = cacheOf(index).overriders;
    var cached = cache.get(methodId);
    if (cached) return cached;
    var reached = new Map([[methodId, 'RESOLVED']]);
    var out = [];
    var frontier = [methodId];
    for (var depth = 0; depth < MAX_HIERARCHY_DEPTH && frontier.length; depth++) {
      var next = [];
      for (var f = 0; f < frontier.length; f++) {
        var soFar = reached.get(frontier[f]);
        // 같은 메서드로 가는 엣지가 여럿이면 확실한 것을 먼저 본다(입력 순서에 기대지 않게).
        var edges = inOf(index, frontier[f], 'OVERRIDES').slice().sort(function (a, b) {
          return G.compareText(a.from, b.from) || (CONFIDENCE_RANK[a.confidence] || 0) - (CONFIDENCE_RANK[b.confidence] || 0);
        });
        for (var i = 0; i < edges.length; i++) {
          var e = edges[i];
          if (reached.has(e.from)) continue;
          var confidence = (CONFIDENCE_RANK[soFar] || 0) >= (CONFIDENCE_RANK[e.confidence] || 0) ? soFar : e.confidence;
          reached.set(e.from, confidence);
          next.push(e.from);
          if (!isAbstract(index, e.from)) out.push({ id: e.from, confidence: confidence === 'RESOLVED' ? 'RESOLVED' : 'INFERRED', edge: e });
        }
      }
      frontier = next;
    }
    out.sort(function (a, b) { return G.compareText(a.id, b.id); });
    cache.set(methodId, out);
    return out;
  }

  /** methodId가 재정의한 상위 메서드 전부(OVERRIDES를 끝까지 따라간다). */
  function overridden(index, methodId) {
    var result = [];
    var seen = new Set([methodId]);
    var frontier = [methodId];
    for (var depth = 0; depth < MAX_HIERARCHY_DEPTH && frontier.length; depth++) {
      var next = [];
      for (var f = 0; f < frontier.length; f++) {
        var out = outOf(index, frontier[f], 'OVERRIDES');
        for (var i = 0; i < out.length; i++) {
          if (seen.has(out[i].to)) continue;
          seen.add(out[i].to);
          result.push(out[i].to);
          next.push(out[i].to);
        }
      }
      frontier = next;
    }
    return result;
  }

  /** typeId의 (전이적) 상위 타입(EXTENDS, IMPLEMENTS). 자신은 넣지 않는다. */
  function supertypes(index, type) {
    var cache = cacheOf(index).supertypes;
    var cached = cache.get(type);
    if (cached) return cached;
    var result = new Set();
    var frontier = [type];
    for (var depth = 0; depth < MAX_HIERARCHY_DEPTH && frontier.length; depth++) {
      var next = [];
      for (var f = 0; f < frontier.length; f++) {
        var out = index.out(frontier[f]);
        for (var i = 0; i < out.length; i++) {
          var e = out[i];
          if ((e.kind !== 'EXTENDS' && e.kind !== 'IMPLEMENTS') || e.to === type || result.has(e.to)) continue;
          result.add(e.to);
          next.push(e.to);
        }
      }
      frontier = next;
    }
    cache.set(type, result);
    return result;
  }

  /** sub가 sup과 같거나 그 (전이적) 하위 타입인지. */
  function isSubtype(index, sub, sup) {
    return sub != null && sup != null && (sub === sup || supertypes(index, sub).has(sup));
  }

  function related(index, a, b) {
    return isSubtype(index, a, b) || isSubtype(index, b, a);
  }

  function hasSubtypes(index, type) {
    return inOf(index, type, 'EXTENDS').length > 0 || inOf(index, type, 'IMPLEMENTS').length > 0;
  }

  /** typeId의 (전이적) 하위 타입(ID 순). */
  function subtypes(index, type) {
    var cache = cacheOf(index).subtypes;
    var cached = cache.get(type);
    if (cached) return cached;
    var seen = new Set();
    var frontier = [type];
    for (var depth = 0; depth < MAX_HIERARCHY_DEPTH && frontier.length; depth++) {
      var next = [];
      for (var f = 0; f < frontier.length; f++) {
        ['EXTENDS', 'IMPLEMENTS'].forEach(function (kind) {
          inOf(index, frontier[f], kind).forEach(function (e) {
            if (e.from === type || seen.has(e.from)) return;
            seen.add(e.from);
            next.push(e.from);
          });
        });
      }
      frontier = next;
    }
    var result = Array.from(seen).sort(G.compareText);
    cache.set(type, result);
    return result;
  }

  /** 소스에 정의가 있는 클래스이고 추상이 아닌지(인터페이스·추상 클래스·애너테이션이 아님). */
  function isConcreteType(index, type) {
    var node = index.node(type);
    if (!node || node.kind !== 'JAVA_TYPE') return false;
    var typeKind = node.attrs.typeKind;
    return typeKind !== 'interface' && typeKind !== 'annotation' && listAttr(node, 'modifiers').indexOf('abstract') < 0;
  }

  /** candidates 값(타입 ID, 바이너리 이름, 빈 ID나 빈 이름)을 타입 ID로 바꾼다. */
  function candidateType(index, value) {
    if (value == null || !String(value).trim()) return null;
    var text = String(value);
    if (text.indexOf(TYPE_PREFIX) === 0) return text;
    var isBeanId = text.indexOf('spring:bean:') === 0;
    var beanId = isBeanId ? text : 'spring:bean:' + text;
    if (index.node(beanId)) {
      var incoming = inOf(index, beanId, 'DEFINES_BEAN');
      for (var i = 0; i < incoming.length; i++) {
        if (kindOf(index, incoming[i].from) === 'JAVA_TYPE') return incoming[i].from;
      }
    }
    return isBeanId ? null : TYPE_PREFIX + text;
  }

  /**
   * callerType이 interfaceType으로 주입받는 구현 타입. 정보가 없으면 빈 배열.
   *  - INJECTS(callerType→interfaceType)의 attrs.candidates
   *  - attrs.resolvedFrom이 interfaceType인 INFERRED INJECTS(callerType→구현 타입)가 정확히 하나일 때 그 대상
   *  - 대상이 interfaceType을 구현·상속하는 구체 타입인 INJECTS(신뢰도와 resolvedFrom의 형식을 가리지 않는다): XML 주입
   *    (resolvedFrom=spring:bean:…, via=xml), 필드 타입이 구현 클래스인 직접 주입. interfaceType으로 가는 주입이 후보 없이 따로 있으면
   *    (어느 구현이 들어오는지 모르는 주입) 이 규칙으로는 좁히지 않는다. 다만 interfaceType 자신이 구체 타입이면 그것도 후보에 넣는다.
   */
  function injectedImplementations(index, callerType, interfaceType) {
    var allowed = [];
    var interfaceName = G.idRemainder(interfaceType);
    var inferredTargets = [];
    var direct = [];
    var unknownInjection = false;
    var add = function (t) { if (t && allowed.indexOf(t) < 0) allowed.push(t); };
    var out = outOf(index, callerType, 'INJECTS');
    for (var i = 0; i < out.length; i++) {
      var e = out[i];
      var a = e.attrs || {};
      if (e.to === interfaceType) {
        var candidates = listAttr(e, 'candidates');
        if (!candidates.length) unknownInjection = true;
        candidates.forEach(function (c) { add(candidateType(index, c)); });
      }
      if (e.confidence === 'INFERRED' && a.resolvedFrom != null && (a.resolvedFrom === interfaceType || a.resolvedFrom === interfaceName)
        && inferredTargets.indexOf(e.to) < 0) inferredTargets.push(e.to);
      if (e.to !== interfaceType && isConcreteType(index, e.to) && isSubtype(index, e.to, interfaceType) && direct.indexOf(e.to) < 0) direct.push(e.to);
    }
    if (inferredTargets.length === 1) add(inferredTargets[0]);
    if (direct.length) {
      if (!unknownInjection) direct.forEach(add);
      else if (isConcreteType(index, interfaceType)) { direct.forEach(add); add(interfaceType); }
    }
    return allowed;
  }

  /** EXTENDS로 가는 상위 클래스. 여럿이면 ID가 앞선 것, 없으면 null. */
  function superclass(index, type) {
    var first = null;
    var out = outOf(index, type, 'EXTENDS');
    for (var i = 0; i < out.length; i++) {
      if (first == null || out[i].to < first) first = out[i].to;
    }
    return first;
  }

  /**
   * callerType에 주입되는 구현 타입이 methodId 호출에서 실제로 실행하는 메서드(ID 순). 주입 정보가 없으면 빈 배열.
   * 후보 타입마다 그 타입에 선언된 재정의가 있으면 그것, 없으면 EXTENDS를 따라 올라가며 가장 가까운 상위 클래스의 구현을 고른다.
   * 후보 타입의 하위 클래스가 재정의한 것은 고르지 않는다. methodId 자신도 본문이 있으면 고를 수 있다.
   */
  function injectedTargets(index, callerType, methodId) {
    var candidates = injectedImplementations(index, callerType, ownerType(index, methodId));
    if (!candidates.length) return [];
    var byOwner = new Map();
    overriders(index, methodId).forEach(function (m) {
      var owner = ownerType(index, m.id);
      if (!byOwner.has(owner)) byOwner.set(owner, m.id);
    });
    if (!isAbstract(index, methodId)) {
      var self = ownerType(index, methodId);
      if (!byOwner.has(self)) byOwner.set(self, methodId);
    }
    var targets = [];
    candidates.forEach(function (candidate) {
      var walked = new Set();
      var current = candidate;
      for (var i = 0; i < MAX_HIERARCHY_DEPTH && current != null && !walked.has(current); i++) {
        walked.add(current);
        var impl = byOwner.get(current);
        if (impl != null) { if (targets.indexOf(impl) < 0) targets.push(impl); break; }
        current = superclass(index, current);
      }
    });
    return targets.sort(G.compareText);
  }

  // ---------- 수신자 문맥 (F-015-R29): 팬아웃 엣지를 경로상 가장 최근의 구체 수신자 타입으로 좁힌다.
  //
  // 제네릭 상위 DAO의 메서드 하나(CrudDao<T>#findList)에서는 하위 매퍼마다 같은 id의 구문으로 EXECUTES가 나간다(링커가 추론한 것).
  // 어느 구문이 실행되는지는 호출 지점의 수신자 타입이 정하므로, 걸음마다 문맥을 싣는다.
  //   아래 방향: { type: 이 메서드를 실행하는 객체의 타입, enclosing: 그 메서드의 객체가 아니라 그것을 부른 쪽 객체의 타입이면 true }
  //   위 방향:   { type: 이 메서드를 실행하는 객체가 맞아야 하는 타입(쉼표로 여럿), origin: 팬아웃이 나가는 메서드, exact: 상·하위 관계로만 맞춰 봄 }

  function makeContext(type, enclosing, origin, exact) {
    return { type: type, enclosing: !!enclosing, origin: origin || null, exact: !!exact };
  }

  function contextKey(context) {
    return context ? (context.enclosing ? '~' : '') + context.type + (context.origin == null ? '' : '@' + context.origin) : '';
  }

  /**
   * EXECUTES가 가리키는 구문의 namespace 타입 ID. 엣지의 namespace, 없으면 구문 노드의 namespace,
   * 그것도 없으면 매퍼 구문 ID(mybatis:stmt:<namespace>.<id>)에서 읽는다.
   */
  function namespaceOf(index, executes) {
    var namespace = executes.attrs ? executes.attrs.namespace : null;
    if (namespace == null) {
      var stmt = index.node(executes.to);
      namespace = stmt ? stmt.attrs.namespace : null;
    }
    if (namespace == null && String(executes.to).indexOf('mybatis:stmt:') === 0) {
      var rest = G.idRemainder(executes.to);
      var dot = rest.lastIndexOf('.');
      namespace = dot > 0 ? rest.substring(0, dot) : null;
    }
    return typeId(namespace);
  }

  /**
   * 메서드가 물려준 구문으로 가는 EXECUTES인지: 구문의 namespace가 그 메서드를 가진 타입의 하위 타입이거나,
   * 엣지에 inherited=true(또는 링커의 declaredIn)가 있다. 그 메서드를 가진 타입 자신의 구문이면 아니다.
   */
  function inheritedStatement(index, methodId, executes) {
    var namespace = namespaceOf(index, executes);
    if (namespace == null) return false;
    var owner = ownerType(index, methodId);
    if (namespace === owner) return false;
    var a = executes.attrs || {};
    return a.inherited === true || a.declaredIn != null || isSubtype(index, namespace, owner);
  }

  /** 리플렉션으로 추론한 호출 가운데 팬아웃인지: callType=reflection이고 viaTypes(그 대상으로 타입 인자를 고정한 하위 타입들)가 있다. */
  function reflectiveFanOut(e) {
    return e.kind === 'CALLS' && !!e.attrs && e.attrs.callType === CALL_REFLECTION && listAttr(e, 'viaTypes').length > 0;
  }

  function viaTypes(e) {
    var types = [];
    listAttr(e, 'viaTypes').forEach(function (name) {
      var id = typeId(name);
      if (id) types.push(id);
    });
    return types;
  }

  /**
   * type(과 그 상위 타입)이 묶는 타입. 상위 타입으로 가는 EXTENDS/IMPLEMENTS 엣지에 typeArguments가 하나라도 있으면 그것만,
   * 없으면 DEPENDS_ON·ASSOCIATES·INJECTS로 참조하는 타입 전부다(타입 인자가 아닌 타입도 섞이므로 넓게 잡힌다).
   */
  function boundTypes(index, type) {
    var cache = cacheOf(index).bound;
    var cached = cache.get(type);
    if (cached) return cached;
    var args = new Set();
    var referenced = new Set();
    [type].concat(Array.from(supertypes(index, type))).forEach(function (t) {
      index.out(t).forEach(function (e) {
        if (e.kind === 'EXTENDS' || e.kind === 'IMPLEMENTS') {
          listAttr(e, 'typeArguments').forEach(function (argument) {
            var id = typeId(argument);
            if (id) args.add(id);
          });
        } else if (e.kind === 'DEPENDS_ON' || e.kind === 'ASSOCIATES' || e.kind === 'INJECTS') referenced.add(e.to);
      });
    });
    var result = Array.from(args.size ? args : referenced);
    cache.set(type, result);
    return result;
  }

  /** 문맥 타입 contextType이 팬아웃 엣지의 타입 target을 고르는지: 두 타입이 상·하위 관계이거나, contextType이 묶는 타입 가운데 target(또는 그 하위 타입)이 있다. */
  function matchesType(index, contextType, target) {
    if (contextType == null || target == null) return false;
    if (related(index, contextType, target)) return true;
    var bound = boundTypes(index, contextType);
    for (var i = 0; i < bound.length; i++) if (isSubtype(index, bound[i], target)) return true;
    return false;
  }

  /** 문맥 타입이 팬아웃 엣지를 고르는지. 상속 구문은 namespace와 matchesType으로, 리플렉션 호출은 viaTypes 가운데 문맥 타입과 상·하위 관계인 것이 있는지로 본다. */
  function selects(index, contextType, fanOut) {
    if (fanOut.kind === 'EXECUTES') return matchesType(index, contextType, namespaceOf(index, fanOut));
    var via = viaTypes(fanOut);
    for (var i = 0; i < via.length; i++) if (related(index, contextType, via[i])) return true;
    return false;
  }

  /** methodId에서 팬아웃 엣지(물려준 구문으로 가는 EXECUTES, 리플렉션 호출)가 나가는지. */
  /**
   * 문맥이 고른 팬아웃 엣지들 가운데 e의 대상이 하나로 정해졌는지. 상속 구문(EXECUTES)은 고른 엣지들의 namespace가 하나일 때,
   * 리플렉션 호출은 같은 호출 지점에서 고른 대상이 하나일 때다(객체 하나는 매퍼 하나의 구문만 실행한다).
   */
  function singleTarget(index, e, selected) {
    var siteKey = function (x) { var at = x.location; return at && at.file != null ? at.file + ':' + (at.startLine || 0) + ':' + (at.startColumn || 0) : null; };
    var targets = new Set();
    selected.forEach(function (other) {
      if (other.kind !== e.kind) return;
      if (e.kind === 'EXECUTES') targets.add(String(namespaceOf(index, other)));
      else if (siteKey(other) === siteKey(e)) targets.add(other.to);
    });
    return targets.size <= 1;
  }

  function hasFanOut(index, methodId) {
    var cache = cacheOf(index).fanOut;
    var cached = cache.get(methodId);
    if (cached !== undefined) return cached;
    var found = false;
    var out = index.out(methodId);
    for (var i = 0; i < out.length && !found; i++) {
      var e = out[i];
      if (e.kind === 'EXECUTES') found = inheritedStatement(index, methodId, e);
      else if (e.kind === 'CALLS') found = reflectiveFanOut(e);
    }
    cache.set(methodId, found);
    return found;
  }

  /**
   * callerId의 메서드가 targetId를 자기 객체에 대고 부르는지: super 호출, 같은 타입 안의 호출,
   * 상위 타입에서 물려받은 메서드를 수신자 타입 표시 없이 부르는 호출(수신자가 다른 타입의 객체면 java가 receiverType을 남긴다).
   */
  function thisCall(index, callerId, targetId, callType) {
    if (callType === 'super') return true;
    var callerOwner = ownerType(index, callerId);
    var targetOwner = ownerType(index, targetId);
    return callerOwner === targetOwner || isSubtype(index, callerOwner, targetOwner);
  }

  /**
   * 수신자 문맥에 따라 전개 결과가 달라지는 메서드인지: 팬아웃 엣지가 나가거나, 문맥을 이어받는 호출(같은 객체의 메서드 호출,
   * 다른 객체의 팬아웃 메서드 호출, 구현 전개)로 그런 메서드에 닿는다. 문맥은 이런 메서드로 갈 때만 싣는다
   * (상관없는 메서드까지 문맥별로 따로 펼치지 않게).
   */
  function receiverSensitive(index, methodId) {
    return sensitive(index, methodId, new Set(), 0) === true;
  }

  /** 확정된 결과(true/false). 순환이나 깊이 제한 때문에 "아니다"를 확정하지 못했으면 null. */
  function sensitive(index, methodId, visiting, depth) {
    var cache = cacheOf(index).sensitive;
    var cached = cache.get(methodId);
    if (cached !== undefined) return cached;
    if (hasFanOut(index, methodId)) { cache.set(methodId, true); return true; }
    if (depth > MAX_SENSITIVE_DEPTH || visiting.has(methodId)) return null;
    visiting.add(methodId);
    var incomplete = false;
    var found = false;
    var next = [];
    var out = outOf(index, methodId, 'CALLS');
    for (var i = 0; i < out.length; i++) {
      var e = out[i];
      var a = e.attrs || {};
      if (!isJavaMethodId(e.to) || a.receiverType != null || Object.prototype.hasOwnProperty.call(CONTEXT_BREAKING, a.callType)) continue;
      if (thisCall(index, methodId, e.to, a.callType)) next.push(e.to);
      else if (hasFanOut(index, e.to)) { found = true; break; }
    }
    if (!found) overriders(index, methodId).forEach(function (m) { next.push(m.id); });
    for (var k = 0; !found && k < next.length; k++) {
      var below = sensitive(index, next[k], visiting, depth + 1);
      found = below === true;
      if (below === null) incomplete = true;
    }
    visiting.delete(methodId);
    if (found || !incomplete) { cache.set(methodId, found); return found; }
    return null;
  }

  /** callerId(문맥 callerContext)에서 호출 엣지 e로 간 메서드의 문맥. 문맥과 무관한 메서드로 가면 null. */
  function calleeContext(index, callerId, callerContext, e) {
    var target = e.to;
    if (!isJavaMethodId(target)) return null;
    var a = e.attrs || {};
    var receiver = typeId(a.receiverType);
    if (receiver != null) return receiverSensitive(index, target) ? makeContext(receiver) : null;
    if (Object.prototype.hasOwnProperty.call(CONTEXT_BREAKING, a.callType)) return null;
    var thisType = callerContext && !callerContext.enclosing ? callerContext.type : null;
    if (thisCall(index, callerId, target, a.callType)) {
      var callerOwner = ownerType(index, callerId);
      var type = thisType != null ? thisType : callerOwner === ownerType(index, target) ? null : callerOwner;
      return type != null && receiverSensitive(index, target) ? makeContext(type) : null;
    }
    // 다른 객체의 메서드: 그 객체가 타입 매개변수로 선언된 필드일 수 있으므로 감싼 객체의 타입을 한 걸음만 넘긴다.
    return thisType != null && hasFanOut(index, target) ? makeContext(thisType, true) : null;
  }

  /**
   * 구현으로 펼친 메서드의 문맥. 수신자 타입 R로 부른 인터페이스 메서드의 구현이면: 구현을 가진 타입이 R의 하위면 그 타입,
   * R이 그 타입의 하위면 R, 둘 다 아니면 R의 하위이면서 그 타입의 하위인 타입이 하나일 때 그것이다.
   */
  function implementationContext(index, context, implId) {
    if (!context || context.enclosing || !receiverSensitive(index, implId)) return null;
    var owner = ownerType(index, implId);
    if (isSubtype(index, owner, context.type)) return makeContext(owner);
    if (isSubtype(index, context.type, owner)) return context;
    var both = subtypes(index, context.type).filter(function (sub) { return isSubtype(index, sub, owner); });
    return both.length === 1 ? makeContext(both[0]) : context;
  }

  /**
   * 정적 타입이 receiverType인 수신자에 대고 부른 호출이 implOwner에 선언된 재정의로 디스패치될 수 있는지:
   * 두 타입이 상·하위 관계이거나, implOwner의 하위 타입 가운데 receiverType의 하위 타입이 있다.
   */
  function mayDispatch(index, receiverType, implOwner) {
    if (related(index, implOwner, receiverType)) return true;
    var subs = subtypes(index, implOwner);
    for (var i = 0; i < subs.length; i++) if (isSubtype(index, subs[i], receiverType)) return true;
    return false;
  }

  /** type이 need(위 방향의 조건)를 채우는지. */
  function satisfies(index, type, need) {
    var required = String(need.type).split(',');
    for (var i = 0; i < required.length; i++) {
      if (need.exact ? related(index, type, required[i]) : matchesType(index, type, required[i])) return true;
    }
    return false;
  }

  /** type이 originMethodId에서 나가는 팬아웃 엣지 가운데 하나라도 고르는지. */
  function selectsAnyFanOut(index, type, originMethodId) {
    if (originMethodId == null) return false;
    var out = index.out(originMethodId);
    for (var i = 0; i < out.length; i++) {
      var e = out[i];
      var fanOut = e.kind === 'EXECUTES' ? inheritedStatement(index, originMethodId, e) : e.kind === 'CALLS' ? reflectiveFanOut(e) : false;
      if (fanOut && selects(index, type, e)) return true;
    }
    return false;
  }

  // ---------- 모듈 (F-015-R36)

  /**
   * 노드가 든 모듈의 루트 경로: 노드 위치의 파일 경로에서 /src/main/·/src/test/ 앞부분이다(루트 모듈이면 빈 문자열).
   * 위치가 없거나 그런 조각이 없으면 null.
   */
  function moduleOf(index, nodeId) {
    var cache = cacheOf(index).module;
    var cached = cache.get(nodeId);
    if (cached !== undefined) return cached;
    var result = moduleOfFile(fileOf(index, nodeId));
    cache.set(nodeId, result);
    return result;
  }

  function fileOf(index, nodeId) {
    var node = index.node(nodeId);
    return node && node.location && node.location.file != null ? String(node.location.file) : null;
  }

  function moduleOfFile(file) {
    if (file == null) return null;
    var markers = ['src/main/', 'src/test/'];
    for (var i = 0; i < markers.length; i++) {
      if (String(file).indexOf(markers[i]) === 0) return '';
      var at = String(file).indexOf('/' + markers[i]);
      if (at >= 0) return String(file).substring(0, at);
    }
    return null;
  }

  /** 요청 → 엔드포인트 대응(MATCHES)에 링커가 적은 그 요청의 웹 모듈 루트 경로(attrs.module). 없으면 null. */
  function matchModule(index, requestId, endpointId) {
    var out = index.out(requestId);
    for (var i = 0; i < out.length; i++) {
      var e = out[i];
      if (e.kind === 'MATCHES' && e.to === endpointId && e.attrs && e.attrs.module != null) return String(e.attrs.module);
    }
    return null;
  }

  /** 엔드포인트에서 나가는 엣지가 선언된 파일: 핸들러(ROUTES_TO)는 그 메서드의 파일, 뷰 컨트롤러의 화면(RENDERS)은 엣지의 위치(없으면 화면의 파일). */
  function declaredIn(index, e) {
    if (e.kind === 'RENDERS' && e.location && e.location.file != null) return String(e.location.file);
    return fileOf(index, e.to);
  }

  /**
   * file이 module(모듈 루트 경로. 루트 모듈은 빈 문자열이나 '.')에 든 파일인지. 경로에 src/main/·src/test/가 있으면 그 앞부분이 모듈과 같아야 하고
   * (하위 모듈의 파일은 상위 모듈의 것이 아니다), 없으면 모듈 경로 아래에 있으면 된다.
   */
  function inModule(file, module) {
    if (file == null) return false;
    var root = module === '.' ? '' : /\/$/.test(module) ? module.substring(0, module.length - 1) : module;
    var own = moduleOfFile(file);
    if (own != null) return own === root;
    return root === '' || String(file).indexOf(root + '/') === 0;
  }

  /** 엔드포인트가 핸들러·뷰 컨트롤러의 화면을 둘 이상 가졌는지: 어느 요청(어느 핸들러)에서 왔느냐에 따라 그 아래(위)가 달라진다. */
  function severalHandlers(index, endpointId) {
    var out = index.out(endpointId);
    var n = 0;
    for (var i = 0; i < out.length; i++) if (out[i].kind === 'ROUTES_TO' || out[i].kind === 'RENDERS') n++;
    return n > 1;
  }

  function moduleLabel(module) {
    return '모듈 ' + (module === '' ? '.' : module);
  }

  /** 엔드포인트의 선언(핸들러 메서드, 뷰 컨트롤러 선언)이 둘 이상의 모듈에 있는지. */
  function declaredInSeveralModules(index, endpointId) {
    var modules = new Set();
    index.out(endpointId).forEach(function (e) {
      if (e.kind === 'ROUTES_TO' || e.kind === 'RENDERS') modules.add(String(moduleOfFile(declaredIn(index, e))));
    });
    return modules.size > 1;
  }

  function trimPath(path) {
    var s = String(path).trim().split(/[?#]/)[0];
    return s.length > 1 && /\/$/.test(s) ? s.substring(0, s.length - 1) : s;
  }

  /** path(쿼리·fragment 제외, 끝 슬래시 무시)가 엔드포인트의 경로(resolvedPath, path, ID의 경로)와 같은지. */
  function sameEndpointPath(index, endpointId, path) {
    if (path == null || !String(path).trim()) return false;
    var wanted = trimPath(path);
    var endpoint = index.node(endpointId);
    var rest = G.idRemainder(endpointId);
    var own = [rest.indexOf(' ') < 0 ? rest : rest.substring(rest.indexOf(' ') + 1)];
    if (endpoint) own.push(endpoint.attrs.resolvedPath, endpoint.attrs.path);
    return own.some(function (candidate) { return candidate != null && trimPath(candidate) === wanted; });
  }

  /**
   * 엔드포인트의 URL로 넘어오는 forward·redirect: 그 엔드포인트의 핸들러로 들어오는 callType=forward|redirect인 CALLS(표준 산출물의
   * FlowNavigator.requestEntriesInto). 핸들러가 엔드포인트 여럿을 받으면 엣지의 path가 이 엔드포인트의 경로와 같은 것만 고르고,
   * 그 핸들러의 어느 엔드포인트와도 같지 않으면 가르지 못하므로 넣는다.
   */
  function requestEntriesInto(index, endpointId) {
    var entries = [];
    outOf(index, endpointId, 'ROUTES_TO').forEach(function (route) {
      var siblings = [];
      inOf(index, route.to, 'ROUTES_TO').forEach(function (other) { if (siblings.indexOf(other.from) < 0) siblings.push(other.from); });
      inOf(index, route.to, 'CALLS').forEach(function (c) {
        var callType = c.attrs ? c.attrs.callType : null;
        if (callType !== 'redirect' && callType !== 'forward') return;
        if (siblings.length > 1) {
          var path = c.attrs ? c.attrs.path : null;
          if (!sameEndpointPath(index, endpointId, path) && siblings.some(function (sibling) { return sameEndpointPath(index, sibling, path); })) return;
        }
        entries.push(c);
      });
    });
    return entries;
  }

  // ---------- 표시 옵션에 쓰는 판정 (F-015-R28, R35)

  /** 데이터 적재 구문인지: 스크립트의 리터럴 INSERT를 파일·테이블 단위로 묶은 구문 노드(attrs.dataLoad=true). */
  function isDataLoad(index, nodeId) {
    var node = index.node(nodeId);
    return !!node && node.kind === 'SQL_STATEMENT' && node.attrs.dataLoad === true;
  }

  function hasCallOrExecute(index, methodId) {
    return outOf(index, methodId, 'CALLS').length > 0 || outOf(index, methodId, 'EXECUTES').length > 0;
  }

  /** 메서드 ID(…#name(a,b<c,d>))의 매개변수 수. 시그니처를 모르면((?), 괄호 없음) -1. */
  function parameterCount(methodId) {
    var id = String(methodId);
    var open = id.indexOf('(', id.indexOf('#') + 1);
    var close = id.lastIndexOf(')');
    if (open < 0 || close < open) return -1;
    var params = id.substring(open + 1, close).trim();
    if (!params) return 0;
    if (params === '?') return -1;
    var count = 1;
    var depth = 0;
    for (var i = 0; i < params.length; i++) {
      var c = params.charAt(i);
      if (c === '<') depth++;
      else if (c === '>') depth--;
      else if (c === ',' && depth === 0) count++;
    }
    return count;
  }

  function isUpperCase(c) {
    return c !== c.toLowerCase() && c === c.toUpperCase();
  }

  /** 이름·매개변수 수·수정자가 접근자 꼴이고(getFoo/isFoo는 0개, setFoo는 1개, static 아님) 같은 타입에 대응하는 필드(foo, Foo, isFoo)가 있는지. */
  function fieldAccessor(index, method) {
    var name = method.name == null ? null : String(method.name);
    if (name == null || listAttr(method, 'modifiers').indexOf('static') >= 0) return false;
    var prefix = name.indexOf('get') === 0 ? 'get' : name.indexOf('set') === 0 ? 'set' : name.indexOf('is') === 0 ? 'is' : null;
    if (!prefix || name.length <= prefix.length || !isUpperCase(name.charAt(prefix.length))) return false;
    if (parameterCount(method.id) !== (prefix === 'set' ? 1 : 0)) return false;
    var property = name.substring(prefix.length);
    var owner = G.idRemainder(ownerType(index, method.id));
    var candidates = [property.charAt(0).toLowerCase() + property.substring(1), property];
    if (prefix === 'is') candidates.push(name);
    return candidates.some(function (field) {
      var node = index.node('java:field:' + owner + '#' + field);
      return !!node && node.kind === 'JAVA_FIELD';
    });
  }

  /**
   * 콜트리에서 접어도 되는 접근자 메서드인지(F-015-R43). 이름만 보지 않는다: Lombok이 만들 멤버(synthetic=true이고 lombok이 있는 메서드)이거나,
   * 같은 타입에 대응하는 필드가 있는 getXxx/isXxx(매개변수 0개)·setXxx(1개)이고 static이 아니다. 어느 쪽이든 그 메서드와 그것을 재정의한
   * 메서드 어디에서도 CALLS·EXECUTES가 나가지 않아야 한다. 생성자와 소스 없는 타입의 메서드(external 접기만 따른다)는 아니다.
   */
  /**
   * MyBatis Generator가 만든 조건 빌더의 메서드인지: 메서드를 가진 타입의 단순 이름이 *Example이거나 그 중첩 타입(*Example$Criteria 등)이다.
   */
  function isBuilderCall(index, methodId) {
    if (!isJavaMethodId(methodId)) return false;
    var rest = G.idRemainder(methodId);
    var hash = rest.indexOf('#');
    var type = hash < 0 ? rest : rest.substring(0, hash);
    var simple = type.substring(type.lastIndexOf('.') + 1);
    var dollar = simple.indexOf('$');
    var outer = dollar < 0 ? simple : simple.substring(0, dollar);
    return outer.length > 'Example'.length && outer.lastIndexOf('Example') === outer.length - 'Example'.length;
  }

  /** 부수 효과 없는 생성자인지: 소스에 정의가 있는 생성자이고, 본문에서 나가는 호출이 (역시 부수 효과 없는, 또는 소스 없는) 생성자뿐이며 구문을 실행하지 않는다. */
  function pureConstructor(index, methodId, visiting) {
    var method = index.node(methodId);
    if (!method || method.kind !== 'JAVA_METHOD' || !(method.attrs.constructor === true || String(methodId).indexOf('#<init>(') >= 0)) return false;
    if (isExternal(index, methodId) || visiting.has(methodId)) return true;
    visiting.add(methodId);
    if (outOf(index, methodId, 'EXECUTES').length) return false;
    return outOf(index, methodId, 'CALLS').every(function (e) { return pureConstructor(index, e.to, visiting); });
  }

  function isAccessor(index, methodId) {
    var cache = cacheOf(index).accessor;
    var cached = cache.get(methodId);
    if (cached !== undefined) return cached;
    var accessor = false;
    var method = index.node(methodId);
    if (method && method.kind === 'JAVA_METHOD' && !isExternal(index, methodId) && (isBuilderCall(index, methodId) || pureConstructor(index, methodId, new Set()))) {
      // 생성 코드의 빌더 호출과, 다른 메서드를 부르지 않는 생성자 사슬은 흐름을 읽는 데 도움이 되지 않는다(F-015-R57).
      accessor = true;
    } else if (method && method.kind === 'JAVA_METHOD' && method.attrs.constructor !== true && !isExternal(index, methodId)) {
      var lombok = method.attrs.synthetic === true && method.attrs.lombok != null;
      if (lombok || fieldAccessor(index, method)) {
        accessor = !hasCallOrExecute(index, methodId);
        if (accessor) accessor = overriders(index, methodId).every(function (m) { return !hasCallOrExecute(index, m.id); });
      }
    }
    cache.set(methodId, accessor);
    return accessor;
  }

  /** external 노드인지: 노드나 (메서드의) 소유 타입이 external이거나, 소스에 정의가 없는 Java 메서드. */
  function isExternal(index, nodeId) {
    var cache = cacheOf(index).external;
    var cached = cache.get(nodeId);
    if (cached !== undefined) return cached;
    var result = false;
    var node = index.node(nodeId);
    if (node) {
      if (node.attrs.external === true) result = true;
      else if (node.kind === 'JAVA_METHOD') {
        var owner = index.node(ownerType(index, nodeId));
        result = node.attrs.declared === false || (!!owner && owner.attrs.external === true);
      }
    }
    cache.set(nodeId, result);
    return result;
  }

  /**
   * 필터·인터셉터의 진입 메서드(ID 순): stereotype이 filter·interceptor인 타입의 doFilter, doFilterInternal, preHandle, postHandle,
   * afterCompletion, afterConcurrentHandlingStarted. 표준 산출물의 calltree/filters.md가 시작하는 메서드와 같다(F-015-R39).
   * [{ id, node, typeId, stereotype, label(Type#method(…)) }]
   */
  function filterEntries(index) {
    var entries = [];
    index.nodesOfKind('JAVA_TYPE').forEach(function (type) {
      var stereotype = type.attrs.stereotype;
      if (stereotype !== 'filter' && stereotype !== 'interceptor') return;
      outOf(index, type.id, 'CONTAINS').forEach(function (e) {
        var method = index.node(e.to);
        if (method && method.kind === 'JAVA_METHOD' && Object.prototype.hasOwnProperty.call(FILTER_ENTRY_METHODS, method.name)) {
          entries.push({ id: method.id, node: method, typeId: type.id, stereotype: stereotype, label: G.methodLabel(G.idRemainder(method.id), false) });
        }
      });
    });
    entries.sort(function (a, b) { return G.compareText(a.id, b.id); });
    return entries;
  }

  // ---------- 배치 스텝

  /** 스텝이 쓰는 타입 아래로 펼칠 메서드: 생성자가 아닌 public 메서드(수정자 정보가 없으면 전부). */
  function isEntryMethod(index, methodId) {
    var method = index.node(methodId);
    if (!method || method.attrs.constructor === true || methodId.indexOf('#<init>(') >= 0
      || methodId.indexOf('#lambda$') >= 0 || methodId.indexOf('#<clinit>') >= 0) return false;
    var modifiers = listAttr(method, 'modifiers');
    return !modifiers.length || modifiers.indexOf('public') >= 0;
  }

  function hasIncoming(index, id, kind) {
    return inOf(index, id, kind).length > 0;
  }

  /**
   * 스텝의 USES 대상(타입 또는 빈)이 같은 스텝이 직접 USES하는 메서드로 이미 드러나는지.
   * 링커가 USES(STEP→METHOD)를 더했으면 타입·빈을 거쳐 public 메서드를 전부 펼칠 필요가 없다.
   */
  function coveredByUsedMethod(index, stepId, targetId) {
    var target = kindOf(index, targetId);
    if (target !== 'JAVA_TYPE' && target !== 'SPRING_BEAN') return false;
    var types = new Set();
    if (target === 'SPRING_BEAN') {
      inOf(index, targetId, 'DEFINES_BEAN').forEach(function (e) { types.add(e.from); });
    } else types.add(targetId);
    var out = outOf(index, stepId, 'USES');
    for (var i = 0; i < out.length; i++) {
      var e = out[i];
      if (kindOf(index, e.to) === 'JAVA_METHOD' && (types.has(e.to) || types.has(ownerType(index, e.to)))) return true;
    }
    return false;
  }

  /** 어떤 스텝이 이 타입의 메서드를 직접 USES하는지. */
  function stepUsesMethodsOf(index, type) {
    var out = outOf(index, type, 'CONTAINS');
    for (var i = 0; i < out.length; i++) {
      if (kindOf(index, out[i].to) === 'JAVA_METHOD' && hasIncoming(index, out[i].to, 'USES')) return true;
    }
    return false;
  }

  /** 배치 스텝이 이 타입(또는 이 타입이 정의한 빈)을 구성 요소로 쓰는지. */
  function usedByStep(index, type) {
    if (hasIncoming(index, type, 'USES')) return true;
    var out = outOf(index, type, 'DEFINES_BEAN');
    for (var i = 0; i < out.length; i++) {
      if (hasIncoming(index, out[i].to, 'USES')) return true;
    }
    return false;
  }

  /**
   * 스텝 ID들을 실행 흐름(NEXT) 순서로 늘어놓는다(표준 산출물의 BatchFlow와 같다).
   * 들어오는 NEXT가 없는 스텝부터 흐름을 따라, 스텝은 그것으로 오는 스텝들 뒤에 놓인다. 분기는 ID 순, 순환은 한 번만,
   * 시작 스텝 없이 순환뿐이면 ID가 앞선 스텝부터, NEXT가 걸리지 않은 스텝은 맨 뒤에 ID 순이다.
   */
  function flowOrder(index, stepIds) {
    var steps = Array.from(new Set(stepIds)).sort(G.compareText);
    var inScope = new Set(steps);
    var successors = function (step) {
      var next = [];
      index.out(step).forEach(function (e) {
        if (e.kind === 'NEXT' && inScope.has(e.to) && next.indexOf(e.to) < 0) next.push(e.to);
      });
      return next.sort(G.compareText);
    };
    var hasIncomingNext = new Set();
    var connected = new Set();
    steps.forEach(function (step) {
      successors(step).forEach(function (next) {
        connected.add(step);
        connected.add(next);
        if (next !== step) hasIncomingNext.add(next);
      });
    });
    var visited = new Set();
    // 깊이 우선 탐색의 끝난 순서를 뒤집으면 흐름 순서(위상 순서)가 된다. 형제를 ID 역순으로 돌아야 뒤집었을 때 ID 순이다.
    var visit = function (start, finished) {
      var stack = [{ id: start, next: null, at: 0 }];
      if (visited.has(start)) return;
      visited.add(start);
      while (stack.length) {
        var top = stack[stack.length - 1];
        if (!top.next) top.next = successors(top.id).reverse();
        if (top.at < top.next.length) {
          var n = top.next[top.at++];
          if (!visited.has(n)) { visited.add(n); stack.push({ id: n, next: null, at: 0 }); }
        } else {
          finished.push(top.id);
          stack.pop();
        }
      }
    };
    var finished = [];
    steps.filter(function (s) { return connected.has(s) && !hasIncomingNext.has(s); }).reverse().forEach(function (s) { visit(s, finished); });
    var ordered = finished.reverse();
    steps.forEach(function (s) {
      if (!connected.has(s) || visited.has(s)) return;
      var loop = [];
      visit(s, loop);
      ordered = ordered.concat(loop.reverse());
    });
    steps.forEach(function (s) { if (!connected.has(s)) ordered.push(s); });
    return ordered;
  }

  /** 잡의 자식 가운데 스텝을 NEXT 흐름 순서로 앞에 놓는다. 스텝이 아닌 자식은 그 뒤에 둔다. */
  function orderSteps(index, links) {
    var byId = new Map();
    var others = [];
    links.forEach(function (l) {
      if (kindOf(index, l.nodeId) === 'BATCH_STEP' && !byId.has(l.nodeId)) byId.set(l.nodeId, l);
      else others.push(l);
    });
    return flowOrder(index, Array.from(byId.keys())).map(function (id) { return byId.get(id); }).concat(others);
  }

  // ---------- 걸음의 위치와 순서 (F-015-R31)

  function siteOf(location) {
    if (!location || location.file == null) return null;
    var line = location.startLine || 0;
    var column = location.startColumn || 0;
    // 끝 위치(없거나 시작보다 앞이면 시작과 같다): 같은 줄의 중첩 호출을 평가 순서로 놓는 데 쓴다.
    var endLine = location.endLine >= line ? location.endLine : line;
    var known = location.endLine > line || (location.endLine === line && location.endColumn > column);
    return { file: String(location.file), line: line, column: column, endLine: endLine, endColumn: known ? location.endColumn : column };
  }

  /** 두 호출 지점 가운데 앞선 것(파일, 줄, 열 순). */
  function earlier(a, b) {
    if (!a) return b;
    if (!b) return a;
    if (a.file !== b.file) return a.file < b.file ? a : b;
    if (a.line !== b.line) return a.line < b.line ? a : b;
    return a.column <= b.column ? a : b;
  }

  /**
   * 줄(link)들을 표준 산출물의 "걸음"(같은 노드·같은 표시·같은 문맥이면 하나)으로 묶어 순서를 정한다.
   * 호출되는 쪽: ① [before](callType=modelAttribute) ② 호출 지점의 위치순(줄, 열)으로 놓는 걸음 ③ 나머지(위치 없는 호출, 구현 전개,
   * 테이블 접근, 트리거, 엔드포인트의 핸들러, 포함 관계)는 노드 ID 순. 호출하는 쪽: 전부 노드 ID 순.
   * 한 걸음 안의 줄(READS와 WRITES처럼 뷰어가 따로 보이는 줄)은 관계 이름순이다.
   */
  function orderLinks(links, callees) {
    var steps = new Map();
    links.forEach(function (l) {
      var s = steps.get(l.stepKey);
      if (!s) {
        steps.set(l.stepKey, s = { nodeId: l.nodeId, impl: l.impl, viaInterface: l.viaInterface, context: contextKey(l.context), superCall: l.superCall, callMode: l.callMode || '',
          kind: l.stepKind, site: l.site, detail: l.detail, links: [] });
      } else {
        // 읽기와 쓰기가 함께 있으면 쓰기로 본다. 그 밖에는 선언 순서가 앞선 종류다.
        s.kind = s.kind === 'WRITES' || l.stepKind === 'WRITES' ? 'WRITES' : (KIND_RANK[s.kind] <= KIND_RANK[l.stepKind] ? s.kind : l.stepKind);
        s.site = earlier(s.site, l.site);
        if (l.detail != null && (s.detail == null || l.detail < s.detail)) s.detail = l.detail;
      }
      s.links.push(l);
    });
    var list = Array.from(steps.values());
    list.forEach(function (s) {
      s.before = s.kind === 'CALLS' && s.detail === CALL_MODEL_ATTRIBUTE;
      s.positioned = callees && !s.impl && !s.before && !!s.site && s.site.line > 0 && Object.prototype.hasOwnProperty.call(POSITIONED_KINDS, s.kind);
      s.links.sort(function (a, b) { return G.compareText(a.label, b.label); });
    });
    var byId = function (a, b) {
      return G.compareText(a.nodeId, b.nodeId) || (a.impl ? 1 : 0) - (b.impl ? 1 : 0) || (a.viaInterface ? 1 : 0) - (b.viaInterface ? 1 : 0)
        || G.compareText(a.context, b.context) || (a.superCall ? 1 : 0) - (b.superCall ? 1 : 0) || G.compareText(a.callMode, b.callMode);
    };
    list.sort(callees ? function (a, b) {
      if (a.before !== b.before) return a.before ? -1 : 1;
      if (a.positioned !== b.positioned) return a.positioned ? -1 : 1;
      // 같은 줄에서 시작한 호출은 평가 순서다: 범위가 포함된 쪽(중첩 호출 f(g(x))의 g)이 먼저 끝나므로 끝 위치 순으로 놓는다(F-015-R55).
      if (a.positioned) {
        var order = a.site.line - b.site.line || a.site.endLine - b.site.endLine || a.site.endColumn - b.site.endColumn || a.site.column - b.site.column;
        if (order) return order;
      }
      return byId(a, b);
    } : byId);
    var out = [];
    list.forEach(function (s) {
      s.links.forEach(function (l) { l.stepKind = s.kind; l.stepDetail = s.detail; out.push(l); });
    });
    return out;
  }

  /**
   * nodeId의 자식 관계 목록.
   * direction: 'callees'(호출되는 쪽, 기본) 또는 'callers'(호출하는 쪽).
   * opts.includeColumns: 구문 아래에 컬럼(READS/WRITES → DB_COLUMN)도 넣는다. 기본은 테이블만.
   * opts.allImplementations: 인터페이스 메서드의 구현을 주입 정보로 좁히지 않고 모두 보인다(위로 갈 때도 좁히지 않는다).
   * opts.showAccessors: 접근자 호출 줄을 접지 않는다. opts.showExternal: 소스 없는 메서드(external 노드)의 줄을 접지 않는다.
   *   기본은 표준 산출물과 같이 둘 다 접는다(export.calltree.hideAccessors, hideExternal).
   * opts.includeDataLoad: 데이터 적재 구문(dataLoad=true)으로 가는 줄을 넣는다. 기본은 뺀다(export.includeDataLoad).
   * opts.hideClientRoutes: 호출되는 쪽에서 화면 내 이동(clientRoute=true인 요청: 표준 산출물의 [route] 줄)을 접는다. 기본은 보인다(표준 산출물과 같다).
   *   화면(ui)은 그런 요청이 있는 그래프에서 기본으로 켠다: React 컴포넌트의 트리에서는 이 줄이 대부분을 차지한다.
   * via: 이 노드에 이른 관계(트리의 부모 쪽 link), parentId: 트리의 부모 노드 ID. 기준 노드면 없다.
   *      트리거를 따라갈지(쓰기의 op와 트리거의 사건), 구현을 어디까지 보일지(호출하는 타입의 주입 정보), 어느 팬아웃 엣지를 따라갈지
   *      (via.context: 수신자 문맥), 어느 모듈의 핸들러로 갈지(요청에서 온 엔드포인트) 정할 때 쓴다.
   * 같은 대상·같은 관계 이름의 엣지 여러 개(호출 지점이 여럿)는 하나로 합치고 count와 locations에 남긴다.
   * reversed는 화살표를 거슬러(엣지의 to에서 from으로) 따라갔다는 뜻이다.
   * 표시: impl(인터페이스·추상 메서드의 구현으로 펼친 것), viaInterface(재정의한 상위 메서드의 호출자, viaMethod가 그 상위 메서드),
   *      triggerUncertain(그 쓰기에 트리거가 실제로 일어나는지 알 수 없다), receiverUncertain(수신자 타입에 맞는 대상을 고르지 못해 전부 따라간 줄),
   *      detail(redirect·forward·super·reflection·modelAttribute, 스텝 구성 요소의 역할, `모듈 <경로>`), context(이 노드를 펼칠 때 쓸 수신자 문맥),
   *      columnOnly(테이블 수준 엣지 없이 컬럼 엣지에서 접은 테이블 줄), injected·hidden(주입 정보로 좁혔고 숨긴 구현의 수).
   * 돌려주는 배열의 hidden은 접은 줄의 수다: { accessors, external, dataLoad, layoutUsers(위로 갈 때 접은 "쓰는 화면" 수), routes(접은 화면 내 이동) }.
   */
  function childLinks(index, nodeId, direction, opts, via, parentId) {
    var o = opts || {};
    var callees = direction !== 'callers';
    var narrow = !o.allImplementations;
    var includeDataLoad = o.includeDataLoad === true;
    var self = index.node(nodeId);
    var kind = kindOf(index, nodeId);
    var javaMethod = kind === 'JAVA_METHOD';
    var groups = new Map();
    var weak = [];
    var droppedLoads = new Set();

    function add(otherId, e, reversed, flags) {
      var f = flags || {};
      if (!includeDataLoad && isDataLoad(index, otherId)) { droppedLoads.add(otherId); return null; }
      var label = f.label || linkLabel(e, callees);
      if (f.detail && (e.kind === 'ROUTES_TO' || e.kind === 'MATCHES' || (e.kind === 'RENDERS' && f.detail !== DETAIL_LAYOUT))) label = e.kind + '(' + f.detail + ')';
      // 접두 일치·인자 구문으로 이은 실행은 관계 이름에 밝힌다.
      if (f.detail && e.kind === 'EXECUTES' && !f.label) label = label + '(' + f.detail + ')';
      var superCall = e.kind === 'CALLS' && f.detail === 'super';
      var callMode = e.kind === 'CALLS' && f.detail != null && Object.prototype.hasOwnProperty.call(SEPARATE_CALL_MODES, f.detail) ? f.detail : '';
      var stepKey = otherId + '|' + (f.impl ? 'i' : '') + '|' + (f.viaInterface ? 'v' : '') + (f.context ? '|' + contextKey(f.context) : '') + (callMode ? '|' + callMode : '');
      var key = label + '\u0000' + stepKey + '\u0000' + (f.columnOnly ? 'c' : '');
      var link = groups.get(key);
      var conf = f.confidence || e.confidence || 'RESOLVED';
      // 함께 싣는지 확인하지 못한 스크립트의 함수로 간 호출은 확정이 아니다.
      if (f.detail === DETAIL_LOAD_UNVERIFIED && conf === 'RESOLVED') conf = 'INFERRED';
      var ambiguous = (e.kind === 'CALLS' || e.kind === 'MATCHES' || e.kind === 'INCLUDES') && !!e.attrs && e.attrs.ambiguous === true;
      // 대상이 그래프에 없거나 미해결 노드면 미해결이다.
      var other = index.node(otherId);
      if (!other ? String(otherId).indexOf('db:column:') !== 0 : other.kind === 'UNRESOLVED') conf = 'UNRESOLVED';
      var site = f.noSite ? null : siteOf(e.location);
      // 이 엣지로 본 추론의 까닭. 같은 줄에 모인 엣지 가운데 하나라도 확정이면 그 줄은 확정이다.
      var reason = inferenceOf(e, f.detail || null, conf, !!f.receiverUncertain, f.pick || null, !!f.impl);
      if (!link) {
        groups.set(key, link = {
          nodeId: otherId, kind: e.kind, reversed: !!reversed, label: label,
          confidence: conf, count: 0, locations: [], attrs: e.attrs || {}, edges: [],
          impl: !!f.impl, viaInterface: !!f.viaInterface, viaMethod: f.viaMethod || null, triggerUncertain: false, columnOnly: !!f.columnOnly,
          receiverUncertain: !!f.receiverUncertain, detail: f.detail || null, context: f.context || null, superCall: superCall, callMode: callMode,
          stepKey: stepKey, stepKind: e.kind, site: site, ambiguous: ambiguous, baseInference: reason, inference: reason
        });
      } else {
        link.receiverUncertain = link.receiverUncertain && !!f.receiverUncertain;
        link.ambiguous = link.ambiguous && ambiguous;
        link.site = earlier(link.site, site);
        if (reason == null) link.baseInference = null;
      }
      link.count++;
      link.edges.push(e);
      // 같은 대상으로 가는 엣지가 여럿이면 가장 확실한 것을 쓴다.
      if ((CONFIDENCE_RANK[conf] || 0) < (CONFIDENCE_RANK[link.confidence] || 0)) link.confidence = conf;
      if (f.uncertain) link.triggerUncertain = true;
      // inference: 이 줄이 추론 걸음이면 그 까닭, 확정이면 null.
      link.inference = link.triggerUncertain ? INFERENCE_TRIGGER_UNCERTAIN : link.baseInference;
      var loc = G.formatLocation(e.location);
      if (loc) link.locations.push(loc);
      return link;
    }

    /** 이 구문이 테이블로 가는 테이블 수준 엣지(READS/WRITES)를 갖고 있는지. 있으면 컬럼 엣지는 접을 필요가 없다. */
    function hasTableEdge(statementId, tableId) {
      var out = index.out(statementId);
      for (var k = 0; k < out.length; k++) {
        if ((out[k].kind === 'READS' || out[k].kind === 'WRITES') && out[k].to === tableId) return true;
      }
      return false;
    }

    /** 요청 ↔ 엔드포인트 대응. 링커가 다른 모듈의 엔드포인트와 맞춘 것(crossModule=true)은 추론으로 표시한다. */
    function matchFlags(e, detail) {
      var flags = e.attrs && e.attrs.crossModule === true && (e.confidence || 'RESOLVED') === 'RESOLVED' ? { confidence: 'INFERRED' } : null;
      if (detail == null) return flags;
      flags = flags || {};
      flags.detail = detail;
      return flags;
    }

    var i, e, mode;
    if (callees) {
      var context = via ? via.context || null : null;
      var inherited = [];
      var reflections = [];
      var handlers = [];
      var views = [];
      // @ModelAttribute 메서드는 요청으로 진입한 핸들러보다 먼저 실행된다. 다른 메서드가 Java 호출로 부른 핸들러에서는 실행되지 않는다.
      var entry = requestEntry(via);
      var clientRoute = kind === 'HTTP_REQUEST' && isClientRoute(index, nodeId);
      var outgoing = index.out(nodeId);
      for (i = 0; i < outgoing.length; i++) {
        e = outgoing[i];
        if (e.kind === 'CONTAINS') {
          mode = containsMode(index, e);
          if (!mode || (mode === 'entry' && !isEntryMethod(index, e.to))) continue;
          add(e.to, e, false);
        } else if (e.kind === 'USES') {
          if (!coveredByUsedMethod(index, nodeId, e.to)) add(e.to, e, false, { detail: e.attrs ? e.attrs.role : null });
        } else if (e.kind === 'READS' || e.kind === 'WRITES') {
          if (isColumnId(index, e.to)) {
            if (o.includeColumns && index.node(e.to)) add(e.to, e, false);
            // 컬럼으로 가는 엣지만 있는 테이블도 한 줄로 보인다(R로 세지는 않는다).
            var folded = accessedTable(index, e);
            if (folded && !hasTableEdge(nodeId, folded)) {
              add(folded, e, false, { columnOnly: true, label: e.kind === 'READS' ? 'READS(컬럼)' : linkLabel(e, true) + '(컬럼)' });
            }
          } else add(e.to, e, false);
        } else if (e.kind === 'MATCHES') {
          // 클라이언트 라우트(화면 안의 이동)는 엔드포인트 대상이 아니다: 맞는 엔드포인트가 없다는 미해결 줄을 내지 않는다.
          if (!(clientRoute && unresolvedTarget(index, e))) add(e.to, e, false, matchFlags(e));
        } else if (e.kind === 'ROUTES_TO') handlers.push(e);
        else if (e.kind === 'INCLUDES') add(e.to, e, false, layoutInclude(index, e) ? { detail: DETAIL_LAYOUT, label: 'INCLUDES(layout)' } : null);
        else if (e.kind === 'RENDERS') {
          // 뷰 컨트롤러: 핸들러 없이 엔드포인트가 바로 화면을 그린다(모듈마다 하나씩 있을 수 있다). tiles 정의의 template은 레이아웃이다.
          if (kind === 'HTTP_ENDPOINT') views.push(e);
          else add(e.to, e, false, e.attrs && e.attrs.via === 'tiles' && e.attrs.tilesAttribute !== true ? { detail: DETAIL_LAYOUT, label: 'RENDERS(layout)' }
            : { detail: relationDetail(e) });
        }
        else if (e.kind === 'EXECUTES') {
          // 같은 지점에서 다른 방법(접두 일치 등)으로 풀린 참조: 미해결 줄을 따로 내지 않는다.
          if (resolvedAnotherWay(index, nodeId, e)) continue;
          if (javaMethod && inheritedStatement(index, nodeId, e)) inherited.push(e);
          else add(e.to, e, false, { detail: relationDetail(e) });
        } else if (e.kind === 'CALLS') {
          if (javaMethod && reflectiveFanOut(e)) reflections.push(e);
          else if (entry || !(e.attrs && e.attrs.callType === CALL_MODEL_ATTRIBUTE)) {
            add(e.to, e, false, { detail: callDetail(e), context: javaMethod ? calleeContext(index, nodeId, context, e) : null });
          }
        } else if (FLOW_KINDS[e.kind]) add(e.to, e, false);
      }
      // 팬아웃 엣지: 문맥 타입과 맞는 것만. 맞는 것이 없으면 전부([?]).
      // 문맥이 없으면 상속 구문은 전부(표시 없음), 리플렉션 호출은 전부 따라가되 [?]로 표시한다.
      var followFanOut = function (fanOut, contextType, uncertainWithoutContext) {
        if (!fanOut.length) return;
        var selected = contextType == null ? [] : fanOut.filter(function (x) { return selects(index, contextType, x); });
        var anyMatch = selected.length > 0;
        var uncertain = contextType == null ? uncertainWithoutContext : !anyMatch;
        (anyMatch ? selected : fanOut).forEach(function (x) {
          var call = x.kind === 'CALLS';
          // 문맥이 이 대상 하나를 골랐을 때만 확정이다. 문맥 없이 전부 따라갔거나 여럿과 맞았으면 추론 걸음이다.
          add(x.to, x, false, { detail: call ? callDetail(x) : relationDetail(x), context: call ? calleeContext(index, nodeId, context, x) : null, receiverUncertain: uncertain,
            pick: anyMatch && singleTarget(index, x, selected) ? 'selected' : 'unselected' });
        });
      };
      var contextType = context ? context.type : null;
      followFanOut(inherited, contextType, false);
      followFanOut(reflections, context && !context.enclosing ? contextType : null, true);
      // 엔드포인트 → 핸들러. 메서드와 경로가 같은 엔드포인트는 노드 하나라서 멀티모듈 프로젝트에서는 여러 웹 모듈의 핸들러로 ROUTES_TO가 나간다.
      // 요청에서 왔고 그 요청을 낸 파일과 같은 모듈의 핸들러가 있으면 그것만 따라간다. 남은 핸들러가 여러 모듈에 걸쳐 있으면 줄마다 모듈을 밝힌다.
      // 링커가 그 요청의 웹 모듈을 대응에 적어 두었으면(MATCHES의 module) 경로로 짐작하지 않고 그것을 따른다: 그 모듈의 핸들러와
      // 그 모듈의 선언에서 나온 화면(RENDERS 엣지의 위치)만 따라가고, 없으면 아무것도 따라가지 않는다(F-015-R62).
      var requestId = via && via.kind === 'MATCHES' && parentId != null ? parentId : null;
      var matchedModule = requestId == null ? null : matchModule(index, requestId, nodeId);
      [handlers, views].forEach(function (targets) {
        if (!targets.length) return;
        var followed = targets;
        var requestModule = requestId != null && matchedModule == null ? moduleOf(index, requestId) : null;
        if (matchedModule != null) followed = targets.filter(function (x) { return inModule(declaredIn(index, x), matchedModule); });
        else if (requestModule != null) {
          var sameModule = targets.filter(function (x) { return moduleOf(index, x.to) === requestModule; });
          if (sameModule.length) followed = sameModule;
        }
        var modules = new Set();
        followed.forEach(function (x) { modules.add(String(moduleOf(index, x.to))); });
        followed.forEach(function (x) {
          var module = moduleOf(index, x.to);
          add(x.to, x, false, { detail: modules.size > 1 && module != null ? moduleLabel(module) : null });
        });
      });
      // 테이블을 바꾸면 걸린 트리거가 실행된다(트리거 -TRIGGERS_ON→ 테이블. 실행 흐름은 화살표와 반대다).
      // 테이블이 기준이면 걸린 트리거를 모두, 쓰기로 이른 테이블이면 그 쓰기에 일어나는 트리거만 보인다. 읽기는 트리거를 일으키지 않는다.
      if (isTableKind(kind) && (!via || via.kind === 'WRITES')) {
        var incomingTriggers = inOf(index, nodeId, 'TRIGGERS_ON');
        for (i = 0; i < incomingTriggers.length; i++) {
          e = incomingTriggers[i];
          var fired = !via || parentId == null ? FIRES_YES : fires(index, e.from, parentId, nodeId);
          if (fired !== FIRES_NO) add(e.from, e, true, { uncertain: fired === FIRES_UNCERTAIN });
        }
      }
      // 빈 → 그 빈을 정의한 타입·메서드
      if (kind === 'SPRING_BEAN') {
        inOf(index, nodeId, 'DEFINES_BEAN').forEach(function (d) { add(d.from, d, true); });
      }
      // 인터페이스·추상 메서드 → 직접·간접 구현. super.m()으로 온 메서드는 상위 구현을 명시적으로 부른 것이라 구현으로 펼치지 않는다.
      if (javaMethod && !(via && via.superCall) && isDispatchTarget(index, nodeId)) {
        var impls = overriders(index, nodeId);
        var chosen = null;
        if (narrow && impls.length && parentId != null && kindOf(index, parentId) === 'JAVA_METHOD') {
          var targets = injectedTargets(index, ownerType(index, parentId), nodeId);
          // 주입되는 타입이 이 메서드 자신을 실행하면(본문이 있는 메서드를 그대로 쓰는 타입) 펼칠 구현이 없다.
          if (targets.length) chosen = new Set(targets.filter(function (t) { return t !== nodeId; }));
        }
        var hiddenImpls = chosen ? impls.filter(function (m) { return !chosen.has(m.id); }).length : 0;
        impls.forEach(function (m) {
          if (chosen && !chosen.has(m.id)) return;
          // 근거가 되는 OVERRIDES 사슬에 추론이 있으면 구현 줄도 추론이다.
          var link = add(m.id, m.edge, true, { impl: true, label: hiddenImpls > 0 ? '구현(주입)' : '구현', confidence: m.confidence, noSite: true,
            context: implementationContext(index, context, m.id) });
          if (link && hiddenImpls > 0) { link.injected = true; link.hidden = hiddenImpls; }
        });
      }
    } else {
      // 위 방향의 조건: 이 메서드를 실행하는 객체가 맞아야 하는 타입(구문의 namespace, 리플렉션 호출의 viaTypes).
      var need = via ? via.context || null : null;
      // @ModelAttribute 메서드에서 [before]로 올라온 핸들러: 위로는 엔드포인트(와 redirect·forward로 넘어오는 핸들러)로만 올라간다.
      var entryOnly = javaMethod && isBeforeLink(via);
      var entersByRequest = function (c) {
        var t = c.attrs ? c.attrs.callType : null;
        return t === 'redirect' || t === 'forward';
      };
      var layoutCallers = 0;
      // 트리거에서 대상 테이블로 올라온 경우: 그 트리거를 일으키는 구문만 올라간다.
      var tableLike = isTableKind(kind);
      var writesOnly = tableLike && !!via && via.kind === 'TRIGGERS_ON';
      var firing = new Map();
      var firesFor = function (statementId) {
        if (!writesOnly || parentId == null) return FIRES_YES;
        if (!firing.has(statementId)) firing.set(statementId, fires(index, parentId, statementId, nodeId));
        return firing.get(statementId);
      };
      var access = function (edge, flags) {
        var fired = writesOnly && edge.kind === 'READS' ? FIRES_NO : firesFor(edge.from);
        if (fired === FIRES_NO) return;
        var f = flags || {};
        f.uncertain = fired === FIRES_UNCERTAIN;
        add(edge.from, edge, true, f);
      };
      // 핸들러에서 올라온 엔드포인트: 그 핸들러로 가는 요청만(다른 모듈의 핸들러가 받는 요청은 뺀다).
      var handlerModule = null;
      var handlerModules = new Set();
      var arrivedFrom = [];
      if (kind === 'HTTP_ENDPOINT' && via && parentId != null && (via.kind === 'ROUTES_TO' || via.kind === 'RENDERS')) {
        outOf(index, nodeId, via.kind).forEach(function (x) { if (x.to === parentId) arrivedFrom.push(declaredIn(index, x)); });
      }
      if (kind === 'HTTP_ENDPOINT' && via && via.kind === 'ROUTES_TO' && parentId != null) {
        handlerModule = moduleOf(index, parentId);
        outOf(index, nodeId, 'ROUTES_TO').forEach(function (x) { handlerModules.add(String(moduleOf(index, x.to))); });
      }
      // 선언이 여러 모듈에 있는 엔드포인트에서 시작했으면(어느 핸들러에서 올라온 것이 아니면) 요청 줄마다 그 요청의 웹 모듈을 밝힌다(F-015-R68).
      var tagRequestModules = kind === 'HTTP_ENDPOINT' && !arrivedFrom.length && declaredInSeveralModules(index, nodeId);
      // 엔드포인트에서 시작했으면 그 URL로 넘어오는 forward·redirect 핸들러도 호출자다(핸들러에서 올라왔으면 핸들러의 호출자에 이미 있다. F-015-R72).
      if (kind === 'HTTP_ENDPOINT' && !(via && via.kind === 'ROUTES_TO')) {
        requestEntriesInto(index, nodeId).forEach(function (c) { add(c.from, c, true, { detail: callDetail(c) }); });
      }
      /**
       * 이 메서드를 부르는 호출 엣지 하나를 호출자 줄로 더한다. need가 있으면 맞는 호출자만 남긴다: 엣지의 receiverType(없으면 호출한 쪽
       * 메서드를 가진 타입)이 맞으면 남기고, 다른 팬아웃 대상과 맞으면 빼고, 어느 것과도 맞지 않으면 — 호출한 쪽 타입에 하위 타입이 있으면
       * 조건을 호출한 쪽으로 넘기고, 아니면 [?]로 남긴다. 리플렉션 팬아웃 엣지는 그 viaTypes가 호출한 쪽에 새 조건이 된다.
       */
      var noSubtypeCanSatisfy = function (type, required) {
        var any = false;
        var subs = subtypes(index, type);
        for (var k = 0; k < subs.length; k++) {
          if (!isConcreteType(index, subs[k])) continue;
          if (satisfies(index, subs[k], required) || !selectsAnyFanOut(index, subs[k], required.origin)) return false;
          any = true;
        }
        return any;
      };
      var addCaller = function (c, viaInterface, viaMethod) {
        var callerId = c.from;
        var flags = { detail: callDetail(c), viaInterface: viaInterface, viaMethod: viaMethod };
        if (!isJavaMethodId(callerId)) { add(callerId, c, true, flags); return; }
        var up = reflectiveFanOut(c) ? makeContext(viaTypes(c).join(','), false, callerId, true) : null;
        var callType = c.attrs ? c.attrs.callType : null;
        if (!need || Object.prototype.hasOwnProperty.call(CONTEXT_BREAKING, callType)) { flags.context = up; add(callerId, c, true, flags); return; }
        var receiver = typeId(c.attrs ? c.attrs.receiverType : null);
        var subject = receiver != null ? receiver : ownerType(index, callerId);
        if (satisfies(index, subject, need)) { flags.context = up; add(callerId, c, true, flags); }
        else if (selectsAnyFanOut(index, subject, need.origin)) return; // 이 호출은 다른 namespace의 구문(다른 대상의 리플렉션 호출)을 실행한다
        else if (receiver == null && up == null && hasSubtypes(index, subject)) {
          // 제네릭 상위 클래스: 구체 하위 타입이 모두 다른 팬아웃 대상을 고르면 이 호출로는 오지 않는다(F-015-R44).
          if (!noSubtypeCanSatisfy(subject, need)) { flags.context = need; add(callerId, c, true, flags); }
        }
        else {
          flags.context = up;
          flags.receiverUncertain = receiver != null || thisCall(index, callerId, nodeId, callType);
          add(callerId, c, true, flags);
        }
      };
      var incoming = index.in(nodeId);
      for (i = 0; i < incoming.length; i++) {
        e = incoming[i];
        if (e.kind === 'CONTAINS') {
          mode = containsMode(index, e);
          if (!mode || mode === 'down' || entryOnly) continue;
          if (mode === 'weak') { weak.push(e); continue; }
          // 스텝이 타입(또는 그 빈)만 가리킬 때만 메서드에서 타입으로 올라간다.
          if (mode === 'entry' && !(usedByStep(index, e.from) && !stepUsesMethodsOf(index, e.from))) continue;
          add(e.from, e, true);
        } else if (e.kind === 'READS' || e.kind === 'WRITES') access(e);
        else if (e.kind === 'MATCHES') {
          // 클라이언트 라우트는 엔드포인트를 요청하지 않는다(미해결 대상으로 모인 것).
          if (kind === 'UNRESOLVED' && isClientRoute(index, e.from)) continue;
          // 링커가 요청의 웹 모듈을 적은 대응(MATCHES의 module)은 그 모듈의 핸들러·선언에서 올라왔을 때만 따라간다.
          var matched = e.attrs && e.attrs.module != null ? String(e.attrs.module) : null;
          if (matched != null && arrivedFrom.length) {
            if (arrivedFrom.some(function (file) { return inModule(file, matched); })) add(e.from, e, true, matchFlags(e));
            continue;
          }
          var fromModule = handlerModule == null ? null : moduleOf(index, e.from);
          if (fromModule == null || fromModule === handlerModule || !handlerModules.has(fromModule)) {
            add(e.from, e, true, matchFlags(e, tagRequestModules && matched != null ? moduleLabel(matched === '.' ? '' : matched) : null));
          }
        } else if (e.kind === 'RENDERS') {
          add(e.from, e, true, { detail: relationDetail(e) });
        } else if (e.kind === 'EXECUTES') {
          // 상속 메서드가 실행하는 구문: 그 메서드의 호출자는 수신자가 이 구문의 namespace와 맞아야 한다.
          var required = kindOf(index, e.from) === 'JAVA_METHOD' && inheritedStatement(index, e.from, e) ? makeContext(namespaceOf(index, e), false, e.from) : null;
          add(e.from, e, true, { context: required, detail: relationDetail(e) });
        } else if (e.kind === 'CALLS') {
          if (entryOnly && !entersByRequest(e)) continue;
          if (javaMethod) addCaller(e, false, null);
          else add(e.from, e, true, { detail: callDetail(e) });
        } else if (e.kind === 'USES') { if (!entryOnly) add(e.from, e, true, { detail: e.attrs ? e.attrs.role : null }); }
        else if (e.kind === 'INCLUDES') {
          // 레이아웃을 쓰는 화면 전부로 올라가지 않는다(수만 알린다).
          var layout = layoutInclude(index, e);
          if (!layout || o.expandLayouts === true) add(e.from, e, true, layout ? { detail: DETAIL_LAYOUT, label: 'INCLUDES(layout)' } : null);
          else layoutCallers++;
        }
        else if (FLOW_KINDS[e.kind]) add(e.from, e, true);
      }
      // 테이블의 컬럼만 건드린 구문(테이블 수준 엣지가 없는 구문)도 그 테이블의 호출자다.
      if (tableLike) {
        index.columnsOf(nodeId).forEach(function (column) {
          index.in(column.id).forEach(function (c) {
            if ((c.kind !== 'READS' && c.kind !== 'WRITES') || hasTableEdge(c.from, nodeId)) return;
            access(c, { columnOnly: true, label: c.kind === 'READS' ? 'READS(컬럼)' : linkLabel(c, false) + '(컬럼)' });
          });
        });
      }
      // 트리거 -TRIGGERS_ON→ 테이블: 트리거를 일으키는 것은 테이블의 변경이다.
      if (kind === 'DB_ROUTINE') outOf(index, nodeId, 'TRIGGERS_ON').forEach(function (x) { add(x.to, x, false); });
      // 타입·메서드 -DEFINES_BEAN→ 빈: 스텝이 그 빈을 구성 요소로 쓸 때만 올라간다.
      if ((kind === 'JAVA_TYPE' || javaMethod) && !entryOnly) {
        outOf(index, nodeId, 'DEFINES_BEAN').forEach(function (x) { if (hasIncoming(index, x.to, 'USES')) add(x.to, x, false); });
      }
      // 이 메서드가 (전이적으로) 재정의한 상위 메서드를 부르는 곳은 이 메서드로 디스패치될 수 있다.
      if (javaMethod) {
        overridden(index, nodeId).forEach(function (superMethod) {
          index.in(superMethod).forEach(function (c) {
            if (c.kind !== 'CALLS' && c.kind !== 'ROUTES_TO' && c.kind !== 'USES' && c.kind !== 'EXECUTES') return;
            var callType = c.attrs ? c.attrs.callType : null;
            // super.m()은 상위 구현을 곧바로 부르는 것이라 하위 재정의로 오지 않는다.
            if (c.kind === 'CALLS' && callType === 'super') return;
            if (entryOnly && c.kind !== 'ROUTES_TO' && !(c.kind === 'CALLS' && entersByRequest(c))) return;
            if (narrow && kindOf(index, c.from) === 'JAVA_METHOD') {
              // 호출하는 쪽에 주입되는 구현이 이 메서드를 실행하지 않으면 뺀다(좁힐 수 없으면 넣는다).
              var injected = injectedTargets(index, ownerType(index, c.from), superMethod);
              if (injected.length && injected.indexOf(nodeId) < 0) return;
            }
            if (c.kind !== 'CALLS') {
              add(c.from, c, true, { viaInterface: true, viaMethod: superMethod, detail: c.kind === 'USES' && c.attrs ? c.attrs.role : relationDetail(c) });
              return;
            }
            // 수신자의 타입이 이 메서드를 가진 타입과 무관하면 이 재정의로 디스패치될 수 없다.
            var receiver = typeId(c.attrs ? c.attrs.receiverType : null);
            if (receiver == null && isJavaMethodId(c.from) && callType !== 'static') {
              var callerOwner = ownerType(index, c.from);
              var superOwner = ownerType(index, superMethod);
              // 상위 타입에서 물려받은 메서드를 자기 객체에 대고 부른 것: 수신자는 호출한 쪽의 타입이다.
              if (callerOwner !== superOwner && isSubtype(index, callerOwner, superOwner)) receiver = callerOwner;
            }
            if (receiver != null && !mayDispatch(index, receiver, ownerType(index, nodeId))) return;
            addCaller(c, true, superMethod);
          });
        });
      }
      // 위로 갈 때, 호출자가 하나도 없는 함수만 정의된 모듈(바깥 함수)로 올라간다.
      if (!groups.size) weak.forEach(function (w) { add(w.from, w, true); });
    }

    var links = Array.from(groups.values());
    // 표준 산출물의 걸음으로 묶어 순서를 정한다(걸음의 종류는 CONTAINS 줄을 빼기 전에 정한다: 같은 대상의 CONTAINS와 CALLS는 CONTAINS 걸음이다).
    links = orderLinks(links, callees);
    // 같은 대상에 CONTAINS와 다른 관계(CALLS 등)가 함께 있으면 CONTAINS 줄은 군더더기다.
    var nonContains = new Set();
    links.forEach(function (l) { if (l.kind !== 'CONTAINS') nonContains.add(l.nodeId); });
    // 빼는 CONTAINS 줄이 확정이면 같은 걸음의 남는 줄도 확정이다(표준 산출물은 한 걸음으로 합쳐 하나라도 확정이면 확정으로 본다).
    var sureByContains = new Set();
    links.forEach(function (l) { if (l.kind === 'CONTAINS' && nonContains.has(l.nodeId) && l.inference == null) sureByContains.add(l.stepKey); });
    if (sureByContains.size) links.forEach(function (l) { if (sureByContains.has(l.stepKey)) { l.baseInference = null; if (!l.triggerUncertain) l.inference = null; } });
    links = links.filter(function (l) { return !(l.kind === 'CONTAINS' && nonContains.has(l.nodeId)); });

    // 접기: 소스 없는 메서드(external 노드)의 줄과, 호출되는 쪽에서 CALLS로 닿은 접근자 줄. 기본값은 표준 산출물과 같다.
    var hidden = { accessors: 0, external: 0, dataLoad: droppedLoads.size,
      layoutUsers: !callees && o.expandLayouts !== true && kind === 'JSP_PAGE' ? layoutUsers(index, nodeId) : 0, routes: 0, builders: 0 };
    var foldedExternal = new Set();
    var foldedAccessors = new Set();
    var foldedBuilders = new Set();
    var foldedRoutes = new Set();
    links = links.filter(function (l) {
      // 화면 내 이동(클라이언트 라우트)은 엔드포인트로 이어지지 않는 잎이다. 고르면 접는다.
      if (callees && o.hideClientRoutes === true && l.kind === 'REQUESTS' && isClientRoute(index, l.nodeId)) {
        foldedRoutes.add(l.stepKey);
        return false;
      }
      if (isExternal(index, l.nodeId)) {
        foldedExternal.add(l.stepKey);
        return o.showExternal === true;
      }
      if (callees && l.stepKind === 'CALLS' && isAccessor(index, l.nodeId)) {
        // 빌더 호출(*Example·*Example$Criteria)은 접은 수를 따로 센다.
        (isBuilderCall(index, l.nodeId) ? foldedBuilders : foldedAccessors).add(l.stepKey);
        return o.showAccessors === true;
      }
      return true;
    });
    if (o.showExternal !== true) hidden.external = foldedExternal.size;
    if (o.showAccessors !== true) { hidden.accessors = foldedAccessors.size; hidden.builders = foldedBuilders.size; }
    hidden.routes = foldedRoutes.size;

    if (callees && self && self.kind === 'BATCH_JOB') links = orderSteps(index, links);
    // 배열의 내용이 아니라 덧붙인 정보라서 열거되지 않게 둔다.
    Object.defineProperty(links, 'hidden', { value: hidden, enumerable: false });
    return links;
  }

  /** nodeId에서 한 걸음 갈 때 옵션에 따라 접히는 줄의 수: { accessors, external, dataLoad, layoutUsers, routes }. */
  function hiddenCounts(index, nodeId, direction, opts, via, parentId) {
    return childLinks(index, nodeId, direction, opts, via, parentId).hidden;
  }

  /**
   * 전개 결과가 문맥(직전 노드, 따라온 엣지)에 따라 달라지는 노드인지: 트리거가 걸린 테이블, 호출하는 쪽의 주입 정보에 따라
   * 구현이 달라지는 디스패치 대상. 같은 노드를 한 번만 따라가는 탐색(CRUD의 엔드포인트 기준)이 문맥별로 따로 보게 한다.
   */
  function contextSensitive(index, nodeId) {
    var kind = kindOf(index, nodeId);
    if (isTableKind(kind)) return hasIncoming(index, nodeId, 'TRIGGERS_ON');
    // 핸들러(뷰 컨트롤러의 화면)가 여럿인 엔드포인트: 어느 요청·어느 핸들러에서 왔느냐에 따라 따라가는 쪽이 다르다.
    if (kind === 'HTTP_ENDPOINT') return severalHandlers(index, nodeId);
    if (kind !== 'JAVA_METHOD') return false;
    var count = overriders(index, nodeId).length;
    return (count > 1 || (count === 1 && !isAbstract(index, nodeId))) && isDispatchTarget(index, nodeId);
  }

  /**
   * link로 도착한 nodeId의 전개 결과를 가르는 문맥의 키. 문맥과 무관하면 빈 문자열이다. 방문 표시의 키에 노드 ID 뒤에 붙인다:
   * 수신자 문맥이 다르면 같은 메서드라도 아래가 다르고, super 호출로 온 디스패치 대상은 구현으로 펼치지 않는다.
   */
  function arrivalKey(index, nodeId, link) {
    if (!link) return '';
    var key = link.context ? '|' + contextKey(link.context) : '';
    if (link.superCall && overriders(index, nodeId).length && isDispatchTarget(index, nodeId)) key += '|super';
    // 요청으로 진입한 핸들러 아래에만 요청 전 처리([before])가 붙는다. [before]로 올라온 핸들러에서는 엔드포인트로만 올라간다.
    if (requestEntry(link) && hasBeforeCalls(index, nodeId)) key += '|entry';
    if (isBeforeLink(link) && hasBeforeCalls(index, nodeId)) key += '|before';
    return key;
  }

  function createRoot(index, nodeId, direction, opts) {
    return {
      id: nodeId, node: index.node(nodeId) || null, link: null, parent: null, depth: 0,
      direction: direction === 'callers' ? 'callers' : 'callees', opts: opts || {},
      cycle: false, children: null, hidden: null
    };
  }

  function isOnPath(treeNode, nodeId) {
    for (var t = treeNode; t; t = t.parent) if (t.id === nodeId) return true;
    return false;
  }

  function linksOf(index, treeNode) {
    if (!treeNode._links) {
      // 묶음 줄(synthetic) 아래의 후보는 묶음이 아니라 실제로 호출한 노드(from)가 부모다.
      var caller = treeNode.from || treeNode.parent;
      treeNode._links = childLinks(index, treeNode.id, treeNode.direction, treeNode.opts, treeNode.link, caller ? caller.id : null);
      treeNode.hidden = treeNode._links.hidden;
    }
    return treeNode._links;
  }

  /** 줄이 오버로드 후보 묶음에 드는 것이면 그 호출 지점의 키(줄:열), 아니면 null. */
  function ambiguityKey(link) {
    if (!link.ambiguous || link.impl) return null;
    // 요청 하나가 후보 엔드포인트 여럿과 맞은 것(MATCHES의 ambiguous=true): 그 요청의 후보 전부가 한 묶음이다.
    if (link.kind === 'MATCHES') return 'matches';
    // 동적 데코레이터 이름을 가리지 못해 후보 레이아웃 여럿으로 이은 것(INCLUDES의 ambiguous=true): 같은 선언 지점의 후보가 한 묶음이다.
    if (link.kind === 'INCLUDES') return 'includes' + (link.site ? ':' + link.site.line + ':' + link.site.column : '');
    if (link.kind !== 'CALLS' || !link.site || !(link.site.line > 0)) return null;
    return link.site.line + ':' + link.site.column + (link.detail == null ? '' : ':' + link.detail);
  }

  /** 후보들의 공통 이름: 타입이 모두 같으면 Type#name, 아니면 이름만. */
  function commonName(links) {
    var common = null;
    var name = null;
    links.forEach(function (l) {
      var label = G.methodLabel(G.idRemainder(l.nodeId), false);
      var paren = label.indexOf('(');
      var bare = paren < 0 ? label : label.substring(0, paren);
      name = bare.substring(bare.indexOf('#') + 1);
      common = common == null || common === bare ? bare : '';
    });
    return common ? common : String(name);
  }

  /**
   * 자식을 계산해 treeNode.children에 채우고 돌려준다. 이미 펼친 노드는 그대로 돌려준다. 순환 노드는 자식이 없다.
   * 그래프의 노드가 아닌 줄(synthetic)이 둘 있다(표준 산출물과 같다):
   *  - 한 호출 지점에서 오버로드 후보 여럿으로 간 호출(ambiguous=true)을 묶는 줄. synthetic = { type: 'ambiguous', count, text }이고 후보가 그 자식이다.
   *  - 위로 갈 때 "이 JSP를 레이아웃으로 쓰는 화면 n개"를 접은 줄. synthetic = { type: 'layout-users', count, text }.
   */
  function expand(index, treeNode) {
    if (treeNode.children) return treeNode.children;
    var links = linksOf(index, treeNode);
    var layoutCount = links.hidden ? links.hidden.layoutUsers : 0;
    treeNode._links = null;
    var callees = treeNode.direction !== 'callers';
    var make = function (link, parent) {
      var cycle = isOnPath(treeNode, link.nodeId);
      var child = {
        id: link.nodeId, node: index.node(link.nodeId) || null, link: link, parent: parent, from: treeNode, depth: parent.depth + 1,
        direction: treeNode.direction, opts: treeNode.opts, cycle: cycle, children: cycle ? [] : null, hidden: null
      };
      // 맞는 엔드포인트를 찾지 못한 action 없는 폼: 그 요청의 화면을 그리는 엔드포인트의 경로로 풀어 적는다(F-015-R70).
      if (callees && link.kind === 'MATCHES' && G.unresolvedSelfSubmitMethod(link.nodeId) != null) {
        child.labelText = G.unresolvedSelfSubmitLabel(index, link.nodeId, treeNode.id);
      }
      // 위로 갈 때 만난 공통 JSP(헤더·메뉴·레이아웃): 그것을 포함하는 화면 전부로 올라가지 않는다(표준 산출물의 [shared], F-015-R58).
      var shared = !callees && !(treeNode.opts && treeNode.opts.expandLayouts === true) ? sharedPages(index, link.nodeId) : 0;
      if (shared > 0) { child.shared = shared; child.children = []; }
      return child;
    };
    var children = [];
    for (var i = 0; i < links.length; i++) {
      var key = callees ? ambiguityKey(links[i]) : null;
      var size = 0;
      if (key != null) while (i + size < links.length && ambiguityKey(links[i + size]) === key) size++;
      if (size > 1) {
        var group = links.slice(i, i + size);
        var id = 'ambiguous:' + treeNode.id + '@' + key;
        var holder = {
          id: id, node: null, parent: treeNode, depth: treeNode.depth + 1, direction: treeNode.direction, opts: treeNode.opts, cycle: false, hidden: null,
          // 자기 페이지로 내는 폼의 후보 묶음은 요청 줄과 같은 문구가 되풀이되므로 "후보 엔드포인트"로 적는다(F-015-R71).
          synthetic: key === 'matches' && treeNode.node && treeNode.node.kind === 'HTTP_REQUEST' && treeNode.node.attrs.selfSubmit === true
            ? { type: 'ambiguous', count: size, text: G.SELF_SUBMIT_CANDIDATES, matchesOf: treeNode.id, selfSubmit: true }
            : key === 'matches' || key.indexOf('includes') === 0
            ? { type: 'ambiguous', count: size, text: G.shortLabel(treeNode.node, treeNode.id), matchesOf: treeNode.id }
            : { type: 'ambiguous', count: size, text: commonName(group) },
          link: { nodeId: id, kind: group[0].kind, label: group[0].kind, reversed: false, confidence: 'INFERRED', count: 1, locations: group[0].locations.slice(0, 1), attrs: {},
            edges: [], impl: false, viaInterface: false, triggerUncertain: false, receiverUncertain: false, detail: null, context: null, superCall: false,
            stepKey: id, stepKind: group[0].kind, site: group[0].site, synthetic: true }
        };
        holder.children = group.map(function (l) { return make(l, holder); });
        children.push(holder);
        i += size - 1;
      } else children.push(make(links[i], treeNode));
    }
    if (layoutCount > 0) {
      var lid = 'layout-users:' + treeNode.id;
      children.push({
        id: lid, node: null, parent: treeNode, depth: treeNode.depth + 1, direction: treeNode.direction, opts: treeNode.opts, cycle: false, hidden: null, children: [],
        synthetic: { type: 'layout-users', count: layoutCount, text: G.idRemainder(treeNode.id) + '를 쓰는 화면 ' + layoutCount + '개' },
        link: { nodeId: lid, kind: 'INCLUDES', label: 'INCLUDES(layout)', reversed: true, confidence: 'RESOLVED', count: 1, locations: [], attrs: {}, edges: [],
          impl: false, viaInterface: false, triggerUncertain: false, receiverUncertain: false, detail: null, context: null, superCall: false,
          stepKey: lid, stepKind: 'INCLUDES', site: null, synthetic: true }
      });
    }
    treeNode.children = children;
    return treeNode.children;
  }

  /** 위 방향에서 이 수 이상의 페이지가 include하는 JSP는 공통 화면으로 보고 그 위를 펼치지 않는다. */
  var SHARED_PAGE_THRESHOLD = 5;

  /** 이 JSP가 공통 화면이면(레이아웃이거나 5개 이상의 페이지가 포함한다) 그것을 포함하는 화면 수, 아니면 0. */
  function sharedPages(index, jspId) {
    if (kindOf(index, jspId) !== 'JSP_PAGE') return 0;
    var pages = new Set();
    inOf(index, jspId, 'INCLUDES').forEach(function (e) { if (e.from !== jspId && kindOf(index, e.from) === 'JSP_PAGE') pages.add(e.from); });
    return pages.size >= SHARED_PAGE_THRESHOLD || layoutUsers(index, jspId) > 0 ? pages.size : 0;
  }

  /** 펼치지 않고 자식이 있는지만 본다. */
  function hasChildren(index, treeNode) {
    if (treeNode.cycle) return false;
    if (treeNode.children) return treeNode.children.length > 0;
    var links = linksOf(index, treeNode);
    return links.length > 0 || (!!links.hidden && links.hidden.layoutUsers > 0);
  }

  /**
   * 너비 우선으로 펼친다. maxDepth(기본 20)보다 깊은 곳과 maxNodes(기본 2000)를 넘는 곳은 남겨 두고 truncated로 알린다.
   * limits.expandScreens가 참이 아니면 RENDERS로 이른 노드(아래로는 컨트롤러가 그리는 화면, 위로는 화면을 그리는 컨트롤러)에서 멈춘다.
   * 그 아래는 다른 화면으로의 이동이라 기준 노드의 흐름이 아니다. 멈춘 곳의 수는 screens로 알리고, 손으로는 펼칠 수 있다.
   */
  function expandAll(index, rootNode, limits) {
    var l = limits || {};
    var maxDepth = l.maxDepth == null ? 20 : l.maxDepth;
    var maxNodes = l.maxNodes == null ? 2000 : l.maxNodes;
    var queue = [rootNode];
    var count = 1;
    var truncated = false;
    var screens = 0;
    for (var head = 0; head < queue.length; head++) {
      var t = queue[head];
      if (t.cycle) continue;
      // 화면 이동(컨트롤러가 그리는 화면, redirect·forward로 넘어가는 핸들러)과 레이아웃은 그 너머를 펼치지 않는다(표준 산출물의 기본값).
      if (!t.children && t.link && isLayoutLink(t.link) && !l.expandLayouts && t.direction !== 'callers') {
        if (hasChildren(index, t)) screens++;
        continue;
      }
      if (!t.children && !l.expandScreens && t.link && (t.link.kind === 'RENDERS' || isRedirectLink(t.link) || isForwardLink(t.link))) {
        if (hasChildren(index, t)) screens++;
        continue;
      }
      if (!t.children && (t.depth >= maxDepth || count >= maxNodes)) {
        if (hasChildren(index, t)) truncated = true;
        continue;
      }
      var fresh = !t.children;
      var kids = expand(index, t);
      if (fresh) count += kids.length;
      for (var i = 0; i < kids.length; i++) queue.push(kids[i]);
    }
    return { count: count, truncated: truncated, screens: screens };
  }

  /** 트리 줄에 보일 위치. 관계(호출·참조 지점)의 위치가 먼저고, 없으면 노드의 정의 위치(요청 지점, 구문 위치)다. 없으면 null. */
  function whereOf(treeNode) {
    if (treeNode.link && treeNode.link.locations.length) return treeNode.link.locations[0];
    return treeNode.node ? G.formatLocation(treeNode.node.location) : null;
  }

  /**
   * 줄에 붙일 표식(표준 산출물의 콜트리와 같은 낱말, 같은 순서): via interface, trigger?, ?(수신자 타입에 맞는 대상을 고르지 못함),
   * reflect(리플렉션으로 추론한 호출), before(핸들러보다 먼저 실행되는 @ModelAttribute 메서드).
   */
  function markersOf(link) {
    var out = [];
    if (!link) return out;
    if (link.viaInterface) out.push('via interface');
    if (link.triggerUncertain) out.push('trigger?');
    if (link.receiverUncertain) out.push('?');
    if (link.kind === 'CALLS' && link.detail === CALL_REFLECTION) out.push('reflect');
    if (link.kind === 'CALLS' && link.detail === CALL_MODEL_ATTRIBUTE) out.push('before');
    if (isRedirectLink(link)) out.push('redirect');
    if (isForwardLink(link)) out.push('forward');
    if (isLayoutLink(link)) out.push('layout');
    return out;
  }

  /** 줄 뒤에 괄호로 덧붙일 말: 다른 방언에서 보충한 구문, 공통 화면을 포함하는 화면 수. 없으면 ''. */
  function noteOf(t) {
    var notes = [];
    var borrowed = t.node && t.node.kind === 'SQL_STATEMENT' ? t.node.attrs.borrowedFrom : null;
    // 데이터 적재 묶음이면 고른 방언에 그 테이블의 적재가 없어 남긴 다른 방언 파일의 것이다.
    if (G.borrowedDataLoadNote(t.node)) notes.push(G.borrowedDataLoadNote(t.node));
    else if (borrowed != null && String(borrowed).trim()) notes.push('다른 방언 ' + borrowed + '에서 보충');
    if (t.shared > 0) notes.push('포함하는 화면 ' + t.shared + '개');
    return notes.join(', ');
  }

  /** 트리 줄의 이름. 요청 문맥에서 풀어 쓴 이름(미해결 selfSubmit 대상)이 있으면 그것, 없으면 짧은 이름. */
  function labelOf(t) {
    return t.labelText != null ? t.labelText : G.shortLabel(t.node, t.id);
  }

  function lineOf(index, t) {
    var text = '';
    for (var i = 0; i < t.depth; i++) text += '  ';
    if (t.link) text += t.link.label + ' ';
    markersOf(t.link).forEach(function (m) { text += '[' + m + '] '; });
    if (t.shared > 0) text += '[shared] ';
    text += t.synthetic ? (t.synthetic.type === 'ambiguous' ? '[ambiguous ' + t.synthetic.count + '] ' : '[layout] ') + t.synthetic.text : labelOf(t);
    if (noteOf(t)) text += ' (' + noteOf(t) + ')';
    if (t.link && t.link.count > 1) text += ' ×' + t.link.count;
    if (t.link && t.link.confidence !== 'RESOLVED') text += ' [' + t.link.confidence + ']';
    if (t.cycle) text += ' (순환)';
    else if (!t.children && hasChildren(index, t)) text += ' …';
    return text;
  }

  /** 펼쳐진 부분을 들여쓴 글로 만든다. */
  function toText(index, rootNode) {
    var lines = [];
    (function walk(t) {
      lines.push(lineOf(index, t));
      if (t.children) t.children.forEach(walk);
    })(rootNode);
    return lines.join('\n');
  }

  var api = {
    ROOT_KINDS: ROOT_KINDS,
    childLinks: childLinks,
    hiddenCounts: hiddenCounts, isBuilderCall: isBuilderCall, sharedPages: sharedPages, noteOf: noteOf,
    contextSensitive: contextSensitive,
    arrivalKey: arrivalKey,
    parseTriggerEvent: parseTriggerEvent,
    flowOrder: flowOrder,
    filterEntries: filterEntries,
    isAccessor: isAccessor,
    isExternal: isExternal,
    isDataLoad: isDataLoad,
    isClientRoute: isClientRoute,
    isRedirectLink: isRedirectLink,
    isForwardLink: isForwardLink,
    isLayoutLink: isLayoutLink,
    layoutUsers: layoutUsers,
    moduleOf: moduleOf,
    markersOf: markersOf,
    createRoot: createRoot,
    expand: expand,
    hasChildren: hasChildren,
    expandAll: expandAll,
    whereOf: whereOf,
    labelOf: labelOf,
    toText: toText
  };
  CA.calltree = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
