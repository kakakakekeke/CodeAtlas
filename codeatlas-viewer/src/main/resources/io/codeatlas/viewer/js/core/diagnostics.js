// 진단 목록과 미해결 참조 목록, 그리고 report.md의 "Java 호출자가 없는 매퍼 구문"·"대상 테이블을 정적으로 알 수 없는 구문"에 해당하는 목록.
// DOM을 쓰지 않는다.
(function (root) {
  'use strict';
  if (typeof module === 'object' && module.exports) { require('./graph.js'); require('./calltree.js'); require('./crud.js'); }
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var G = CA.graph;

  /** 화면의 보기(탭). rows는 collect 결과에서 그 보기의 행이 든 키다. */
  var VIEWS = [
    { key: 'unresolved', rows: 'unresolved', label: '미해결 참조', description: '해석하지 못한 참조. 엣지 하나가 한 건이다' },
    { key: 'diagnostics', rows: 'diagnostics', label: '진단', description: '분석 중에 남긴 진단' },
    { key: 'uncalled', rows: 'uncalledStatements', label: 'Java 호출자 없는 구문',
      description: '구문을 실행하는 메서드는 있지만 그 메서드를(재정의·구현 관계인 메서드 포함) 부르는 Java 코드, 엔드포인트, 배치 스텝이 없는 매퍼 구문. report.md의 "Java 호출자가 없는 매퍼 구문"과 같다' },
    { key: 'dynamic', rows: 'dynamicStatements', label: '동적 구문',
      description: '동적 SQL이라 읽고 쓰는 테이블을 알 수 없는 구문. CRUD 매트릭스의 (동적) 열, report.md의 "대상 테이블을 정적으로 알 수 없는 구문"과 같다' },
    { key: 'undeclared', rows: 'undeclaredTables', label: 'DDL에 없는 테이블',
      description: 'SQL에서 참조했지만 DDL에서 정의를 찾지 못한 테이블. 참조하는 구문이 하나뿐이면 의심으로 표시한다(오타이거나, 구문을 끝까지 파싱하지 못해 복구하면서 나온 이름일 수 있다). 구문 수에서 데이터 적재 구문은 뺀다. report.md의 "DDL에 없는 테이블"과 같다' },
    { key: 'dataload', rows: 'dataLoadFiles', label: '데이터 적재 스크립트',
      description: 'SQL 스크립트의 리터럴 INSERT를 파일·테이블 단위로 묶은 데이터 적재 구문(dataLoad=true)을 파일별로 모은 것. 애플리케이션이 실행하는 구문이 아니라서 콜트리와 CRUD에서 기본으로 뺀다. report.md의 "데이터 적재 스크립트"와 같다' },
    { key: 'columns', rows: 'columnMismatches', label: '컬럼 불일치',
      description: 'SQL이 참조하지만 그 테이블의 DDL에 없는 컬럼(정의가 있는 테이블의 declared=false 컬럼). DDL이 오래됐거나 다른 방언·버전의 DDL이 선택됐을 수 있다. ERD에는 넣지 않고 테이블 상세에 따로 나온다. report.md의 "SQL과 DDL의 컬럼 불일치"와 같다' },
    { key: 'requests', rows: 'requestRows', label: '화면 요청', count: 'requests',
      description: '화면(JSP, 스크립트)의 요청 지점을 서버 요청과 그 밖(외부 URL, 정적 페이지 링크, 화면 내 이동)으로 나누고, 서버 요청이 엔드포인트에 연결됐는지(확정·추론) 센다. 아래에 미연결 요청이 위치순으로 나온다. report.md의 "화면 요청"과 같다' },
    { key: 'borrowed', rows: 'borrowedStatements', label: '보충한 구문',
      description: '선택한 방언의 매퍼 파일에 없어 다른 방언 파일의 정의를 쓴 구문(borrowedFrom). SQL이 선택한 방언의 것이 아닐 수 있다. 다른 방언 파일의 데이터 적재 묶음은 여기가 아니라 "데이터 적재 스크립트"에 방언과 함께 나온다. report.md의 "다른 방언 파일에서 보충한 구문"과 같다' }
  ];

  /** 보기 단추에 적을 개수. 보통은 행 수이고, 통계 보기("화면 요청")는 요청 수다. */
  function viewCount(view, data) {
    if (view.count === 'requests') return data.requestStats ? data.requestStats.total : 0;
    return (data[view.rows] || []).length;
  }

  var SEVERITY_RANK = { ERROR: 0, WARN: 1, INFO: 2 };
  var UNRESOLVED_PREFIX = 'unresolved:';
  // 미해결 참조를 만들 때 함께 남는 진단. 같은 지점의 미해결 참조와 이어 준다.
  var REFERENCE_CODES = { UNRESOLVED_REFERENCE: 1, DYNAMIC_SQL: 1 };
  /** report.md와 같은 낱말: 참조하는 구문이 하나뿐인 DDL에 없는 테이블, 데이터 적재 구문만 참조하는 테이블의 비고. */
  var SUSPICIOUS_TABLE = '의심(구문 파싱 복구에서 나왔을 수 있음)';
  var DATA_LOAD_ONLY = '데이터 적재 스크립트만 참조';
  var SYSTEM_CATALOG = '시스템 카탈로그';
  var WHOLE_PROJECT = '프로젝트 전체';

  // 진단 코드가 뜻하는 것(계약 design 3.7). 코드 필터의 설명으로 보인다. 여기 없는 코드도 그래프에 있으면 필터에 그대로 나온다.
  var CODE_DESCRIPTIONS = {
    PARSE_FAILED: '파싱 실패. ERROR는 파일이나 구문을 통째로 얻지 못한 것, WARN은 오류를 복구하고 일부만 분석한 것이다',
    UNSUPPORTED_SYNTAX: '지원하지 않는 구문을 건너뜀',
    UNRESOLVED_REFERENCE: '미해결 엣지·노드를 만들 때마다 남긴다. 항목은 "미해결 참조" 보기에 있다',
    DYNAMIC_SQL: '동적 SQL: 테이블 자리가 동적이거나, 루틴의 동적 SQL 식을 리터럴로 풀지 못했거나, 구문에 ${} 치환이 있다',
    DUPLICATE_OBJECT_NAME: '같은 이름의 테이블·빈이 여러 번 정의됨. 같은 테이블의 정의가 여러 파일에 있으면 한 파일의 것만 쓴다(위치는 버린 파일)',
    ANALYZER_FAILED: '분석기가 예외로 끝남',
    LINKER_FAILED: '링커가 예외로 끝남',
    EXPORTER_FAILED: '출력기가 예외로 끝남',
    SERVICE_LOAD_FAILED: '구현체를 만들지 못함',
    EXCLUDED_DIRECTORY: '기본 제외 디렉터리를 건너뜀(위치는 그 폴더)',
    FILE_NOT_FOUND: '옵션으로 준 경로(--ddl, --classpath)가 없음',
    FILE_NOT_READABLE: '파일·디렉터리를 읽지 못함',
    INVALID_OPTION: '옵션 값이 잘못돼 무시하거나 기본값을 씀',
    SOURCE_ROOT_MISMATCH: '패키지 선언과 파일 경로가 맞지 않아 소스 루트를 정하지 못함',
    EXTERNAL_CALLS_SKIPPED: '설정(java.externalCalls)에 따라 기록하지 않은 외부 메서드 호출 건수',
    DUPLICATE_STATEMENT_ID: '같은 namespace·id·databaseId의 매퍼 구문이 여러 번 정의됨',
    DIALECT_VARIANT_MAPPERS: '방언별 매퍼 여러 벌을 감지해 한 방언의 정의만 씀. 분석 한 번에 1건이고 위치가 없다. 쓴 방언은 구문의 dialectVariant에 있다(옵션 mybatis.dialectVariant)',
    REFLECTION_CALL: '대상을 정적으로 알 수 없어 기록하지 않은 Method.invoke가 있는 메서드(메서드당 1건)',
    FILE_SKIPPED: '내용을 분석하지 않은 파일. 압축된 스크립트는 파일마다 1건, 벤더 스크립트와 벤더 폴더의 페이지는 폴더마다 1건(위치는 그 폴더, 메시지에 건너뛴 파일 수)이다',
    DATA_LOAD_GROUPED: 'SQL 스크립트의 데이터 적재 INSERT를 테이블별 구문 노드(dataLoad=true)로 묶음. 묶음이 생긴 파일마다 1건이다. "데이터 적재 스크립트" 보기에 요약이 있다',
    DDL_NOT_APPLIED: 'upgrade·patch·migration 스크립트의 ALTER TABLE을 현재 DDL에 적용하지 않음(대상 테이블의 CREATE TABLE이 다른 파일에 있어 이미 반영된 변경으로 본다). 파일마다 1건이다',
    UNRESOLVED_BEAN_PLACEHOLDER: '이름에 프로퍼티 자리표시자(${…})가 든 빈 참조를 풀지 못해 타입으로 잇지 않음. 이름별 건수를 한 건에 요약하고 위치가 없다',
    CALLS_SKIPPED: '매개변수·props로 받은 함수의 호출 가운데 대상을 정하지 못해 CALLS로 기록하지 않은 건수(모듈마다 1건, 줄 번호는 메시지에 있다)',
    MAPPER_METHOD_WITHOUT_STATEMENT: '매퍼 인터페이스 메서드에 대응하는 구문이 없음',
    UNDEFINED_ROUTINE: '정의를 찾지 못한 루틴 참조',
    SYNONYM_CYCLE: '시노님이 서로를 가리킴',
    RELATION_NOT_RECORDED: '추론한 관계를 기록할 근거 위치가 다른 관계와 겹침'
  };

  function codeDescription(code) {
    return Object.prototype.hasOwnProperty.call(CODE_DESCRIPTIONS, code) ? CODE_DESCRIPTIONS[code] : '';
  }

  /**
   * 미해결 노드의 분류와 원문. report.md의 "미해결 참조"와 같은 묶음이다.
   * 분석기·링커가 만든 노드는 `unresolved:<분류>:<원문>`이고 attrs.category가 있다(계약 design 3.6).
   * 마무리 단계가 만든 노드는 ID가 엣지가 가리키던 그대로이고 attrs.expectedPrefix와 그 접두사에서 유도한 attrs.category가 있다.
   * category가 없는 전의 그래프는 expectedPrefix에서 같은 표로 유도한다(`mybatis:stmt` → `mybatis-statement`).
   */
  function describe(node, id) {
    var attrs = (node && node.attrs) || {};
    var category = attrs.category || '';
    var text = null;
    if (id.indexOf(UNRESOLVED_PREFIX) === 0) {
      var rest = id.substring(UNRESOLVED_PREFIX.length);
      var colon = rest.indexOf(':');
      if (!category && colon > 0) category = rest.substring(0, colon);
      // sql-dynamic의 ID는 구문 ID라서 원문은 이름에 있다. 그 밖에는 이름이 곧 원문이다.
      text = node && node.name ? node.name : colon > 0 ? rest.substring(colon + 1) : rest;
      return { category: category || '(분류 없음)', label: category || '(분류 없음)', text: text };
    }
    // 마무리 단계가 만든 노드, 또는 그래프에 노드가 없는 ID
    var prefix = attrs.expectedPrefix || (!node || node.kind === 'UNRESOLVED' ? G.idPrefix(id) : null) || '';
    if (!category && prefix) category = G.danglingCategory(prefix);
    if (prefix) text = id.indexOf(prefix + ':') === 0 ? id.substring(prefix.length + 1) : id;
    else text = node && node.name ? node.name : id;
    return { category: category || '(분류 없음)', label: category || '(분류 없음)', text: text };
  }

  function isUnresolvedNode(node) {
    return !!node && node.kind === 'UNRESOLVED';
  }

  function unresolvedRow(index, e) {
    var from = index.node(e.from);
    var to = index.node(e.to);
    // ROUTES_TO처럼 미해결 노드가 from인 엣지가 있다. "참조한 곳"은 해석된 쪽이다.
    var reversed = !isUnresolvedNode(to) && isUnresolvedNode(from);
    var targetId = reversed ? e.from : e.to;
    var refId = reversed ? e.to : e.from;
    var target = index.node(targetId);
    var d = describe(target, targetId);
    // 대상은 해석됐지만 엣지의 신뢰도가 UNRESOLVED인 경우: 엣지 종류로 묶는다(report.md와 같다).
    if (target && target.kind !== 'UNRESOLVED' && !target.attrs.category && !target.attrs.expectedPrefix) d = { category: e.kind, label: e.kind, text: d.text };
    // 미해결 java-call의 원문은 인자를 적지 않은 `name(…)`이라 인자 수만 다른 호출이 같은 대상으로 모인다. 엣지의 argCount로 구분한다.
    var note = d.category === 'java-call' && e.attrs && typeof e.attrs.argCount === 'number' ? '인자 ' + e.attrs.argCount + '개' : '';
    return {
      type: 'UNRESOLVED', category: d.category, categoryLabel: d.label,
      edgeKind: e.kind, confidence: e.confidence || 'RESOLVED',
      fromId: refId, fromLabel: index.label(refId),
      // 맞는 엔드포인트를 찾지 못한 action 없는 폼: 원문 `POST (selfSubmit)` 대신 그 화면을 그리는 엔드포인트의 경로로 풀어 적는다(report.md와 같다).
      targetId: targetId, target: (e.kind === 'MATCHES' && !reversed ? G.unresolvedSelfSubmitLabel(index, targetId, refId) : null) || (e.attrs && e.attrs.text) || d.text, note: note,
      location: G.formatLocation(e.location) || G.formatLocation(target && target.location),
      nodeId: refId, edge: e
    };
  }

  /** OVERRIDES를 한 방향으로 끝까지 따라가 닿는 메서드를 into에 모은다. up이면 재정의한 상위 쪽, 아니면 재정의·구현한 하위 쪽. */
  function related(index, methodId, up, into) {
    var seen = new Set([methodId]);
    var frontier = [methodId];
    while (frontier.length) {
      var next = [];
      frontier.forEach(function (id) {
        (up ? index.out(id) : index.in(id)).forEach(function (e) {
          if (e.kind !== 'OVERRIDES') return;
          var other = up ? e.to : e.from;
          if (seen.has(other)) return;
          seen.add(other);
          into.add(other);
          next.push(other);
        });
      });
      frontier = next;
    }
  }

  /**
   * 메서드를 부르는 곳이 있는지(F-015-R24): 그 메서드, 그것이 (전이적으로) 재정의·구현한 상위 메서드, 그것을 재정의·구현한 메서드 가운데
   * 하나라도 들어오는 CALLS(같은 묶음 안의 호출 제외), USES(배치 스텝의 구성 요소), ROUTES_TO(엔드포인트의 핸들러)가 있으면 true.
   */
  function hasCaller(index, methodId) {
    var family = new Set([methodId]);
    related(index, methodId, true, family);
    related(index, methodId, false, family);
    var found = false;
    family.forEach(function (member) {
      if (found) return;
      index.in(member).forEach(function (e) {
        if ((e.kind === 'CALLS' || e.kind === 'USES' || e.kind === 'ROUTES_TO') && !family.has(e.from)) found = true;
      });
    });
    return found;
  }

  /**
   * Java 호출자가 없는 매퍼 구문(구문 ID 순): 구문을 EXECUTES하는 것이 Java 메서드뿐이고 그 메서드 어디에도 호출자가 없다.
   * 배치 스텝이 직접 EXECUTES하는 구문과 EXECUTES가 하나도 없는 구문은 넣지 않는다. report.md의 같은 절과 판정이 같다.
   * [{ type:'UNCALLED', id, nodeId, statementLabel(ID의 나머지), location, executors: [{id, label}] }]
   */
  function uncalledStatements(index) {
    var out = [];
    index.nodesOfKind('SQL_STATEMENT').forEach(function (n) {
      if (n.id.indexOf('mybatis:stmt:') !== 0) return;
      var executors = [];
      var used = false;
      index.in(n.id).forEach(function (e) {
        if (used || e.kind !== 'EXECUTES') return;
        var from = index.node(e.from);
        // 배치 스텝이 직접 실행하거나, 실행하는 메서드를 부르는 곳이 있다
        if (!from || from.kind !== 'JAVA_METHOD' || hasCaller(index, e.from)) { used = true; return; }
        if (executors.indexOf(e.from) < 0) executors.push(e.from);
      });
      if (used || !executors.length) return;
      executors.sort(G.compareText);
      out.push({
        type: 'UNCALLED', id: n.id, nodeId: n.id, statementLabel: G.idRemainder(n.id), location: G.lineLocation(n.location),
        executors: executors.map(function (id) { return { id: id, label: G.methodLabel(G.idRemainder(id), true) }; })
      });
    });
    out.sort(function (a, b) { return G.compareText(a.id, b.id); });
    return out;
  }

  // ---------- report.md의 "데이터 적재 스크립트", "DDL에 없는 테이블"

  /** READS/WRITES 엣지의 대상을 테이블(뷰)로 접는다. 대상이 테이블·뷰면 그대로, 컬럼이면 소속 테이블, 그 밖이면 null. */
  function accessedTable(index, e) {
    var target = index.node(e.to);
    if (target && (target.kind === 'DB_TABLE' || target.kind === 'DB_VIEW')) return e.to;
    if (target ? target.kind === 'DB_COLUMN' : String(e.to).indexOf('db:column:') === 0) return index.tableOfColumn(e.to);
    return null;
  }

  /** `120`, `30 이상`(행 수를 모르는 구문이 섞임), `행 수 모름`. */
  function rowsText(load) {
    if (!load.unknownRows) return String(load.rows);
    return load.unknownRows === load.statements && load.rows === 0 ? '행 수 모름' : load.rows + ' 이상';
  }

  /**
   * 데이터 적재 구문(dataLoad=true)을 파일별·테이블별로 모은다(표준 산출물의 DataLoads.summarize와 같다).
   * 테이블은 그 구문의 WRITES(컬럼 엣지는 테이블로 접는다) 대상, 파일은 구문 노드의 위치(없으면 구문 ID `sql:stmt:<파일>:<줄>`)다.
   * rowCount가 숫자가 아니면 행 수를 모르는 구문으로 센다.
   * 결과: { ids: Set(적재 구문 ID), tables: Set(적재되는 테이블 ID), summary(없으면 null), files: [{ type:'DATALOAD', file, tableCount, tables: [이름], statements, rows, unknownRows, rowsText, nodeId }] 파일 경로순 }
   */
  function dataLoads(index) {
    var loads = index.nodesOfKind('SQL_STATEMENT').filter(function (n) { return n.attrs.dataLoad === true; })
      .sort(function (a, b) { return G.compareText(a.id, b.id); });
    var ids = new Set();
    var byFile = new Map();
    var tablesLoaded = new Set();
    var total = { statements: 0, rows: 0, unknownRows: 0 };
    var borrowedByDialect = new Map();
    var plus = function (load, rows, unknown) {
      load.statements++;
      load.rows += rows;
      if (unknown) load.unknownRows++;
    };
    loads.forEach(function (n) {
      ids.add(n.id);
      var unknown = typeof n.attrs.rowCount !== 'number';
      var rows = unknown ? 0 : Math.max(0, Math.trunc(n.attrs.rowCount));
      var file = n.location && n.location.file != null ? String(n.location.file) : null;
      if (file == null) {
        var rest = G.idRemainder(n.id);
        var colon = rest.lastIndexOf(':');
        file = colon > 0 ? rest.substring(0, colon) : rest;
      }
      var entry = byFile.get(file);
      if (!entry) byFile.set(file, entry = { type: 'DATALOAD', file: file, tableIds: new Set(), statements: 0, rows: 0, unknownRows: 0, nodeId: n.id, _line: Infinity, borrowed: new Set() });
      // 고른 방언에 그 테이블의 적재가 없어 남긴 다른 방언 파일의 묶음(borrowedFrom)
      var from = n.attrs.borrowedFrom != null && String(n.attrs.borrowedFrom).trim() ? String(n.attrs.borrowedFrom).replace(/\s+/g, ' ').trim() : null;
      if (from != null) { entry.borrowed.add(from); borrowedByDialect.set(from, (borrowedByDialect.get(from) || 0) + 1); }
      // 누르면 갈 곳: 그 파일에서 가장 앞에 있는 적재 구문
      var line = n.location && n.location.startLine > 0 ? n.location.startLine : Infinity;
      if (line < entry._line) { entry._line = line; entry.nodeId = n.id; }
      index.out(n.id).forEach(function (e) {
        if (e.kind !== 'WRITES') return;
        var table = accessedTable(index, e);
        if (table) { entry.tableIds.add(table); tablesLoaded.add(table); }
      });
      plus(entry, rows, unknown);
      plus(total, rows, unknown);
    });
    var files = Array.from(byFile.values()).sort(function (a, b) { return G.compareText(a.file, b.file); });
    files.forEach(function (f) {
      f.tables = Array.from(f.tableIds).sort(G.compareText).map(G.idRemainder);
      f.tableCount = f.tables.length;
      f.rowsText = rowsText(f);
      f.borrowedFrom = Array.from(f.borrowed).sort(G.compareText);
      f.borrowedNote = f.borrowedFrom.length ? '다른 방언 파일: ' + f.borrowedFrom.join(', ') : '';
    });
    var borrowedCount = 0;
    var dialects = Array.from(borrowedByDialect.keys()).sort(G.compareText);
    dialects.forEach(function (dialect) { borrowedCount += borrowedByDialect.get(dialect); });
    var summary = null;
    if (total.statements) {
      summary = {
        files: files.length, tables: tablesLoaded.size, rows: total.rows, unknownRows: total.unknownRows, statements: total.statements,
        text: '파일 ' + files.length + '개, 테이블 ' + tablesLoaded.size + '개, 행 ' + total.rows + '건'
          + (total.unknownRows > 0 ? ' 이상(행 수를 모르는 구문 ' + total.unknownRows + '개)' : ''),
        // report.md의 "데이터 적재 스크립트"와 같은 문장(파일 이름 안내는 뺀다)
        borrowed: borrowedCount,
        borrowedText: borrowedCount ? '이 가운데 묶음 ' + borrowedCount + '개는 고른 방언에 그 테이블의 적재가 없어 다른 방언 파일의 것이다('
          + dialects.map(function (dialect) { return dialect + ' ' + borrowedByDialect.get(dialect) + '개'; }).join(', ') + ').' : ''
      };
    }
    return { ids: ids, tables: tablesLoaded, summary: summary, files: files };
  }

  var statementName = G.statementName;

  /**
   * DDL에 없는데 SQL이 참조하는 컬럼(표준 산출물의 report.md "SQL과 DDL의 컬럼 불일치", F-015-R51): 정의가 있는 테이블·뷰의 declared=false 컬럼.
   * 테이블 ID 순, 그 안에서 컬럼 이름순이다(리포트의 표와 같은 순서).
   * [{ type:'COLUMN_MISMATCH', id(컬럼 ID), nodeId, tableId, table, column, statementCount, statements: [{ id, label }](5개까지) }]
   */
  function columnMismatches(index) {
    var out = [];
    index.nodesOfKind('DB_TABLE').concat(index.nodesOfKind('DB_VIEW')).slice().sort(function (a, b) { return G.compareText(a.id, b.id); }).forEach(function (t) {
      G.undeclaredColumnsOf(index, t.id).forEach(function (c) {
        out.push({ type: 'COLUMN_MISMATCH', id: c.id, nodeId: c.id, tableId: t.id, table: G.idRemainder(t.id), column: c.name, statementCount: c.statementIds.length,
          statements: c.statementIds.slice(0, 5).map(function (id) { return { id: id, label: statementName(index, id) }; }) });
      });
    });
    return out;
  }

  /** 경로가 정적 페이지(.html, .htm)인지. */
  function isStaticPage(path) {
    return /\.html?$/i.test(String(path));
  }

  /**
   * 요청이 정적 페이지를 가리키는 링크인지(표준 산출물 SummaryReport.staticPageLink와 같다): 경로(쿼리·fragment 제외)가 .html·.htm으로 끝나고,
   * 프로젝트에 그 파일이 있거나(요청을 낸 파일의 폴더 기준 상대 경로, 또는 어느 폴더든 그 경로로 끝나는 파일) 요청을 낸 파일 자신이 정적 페이지다.
   */
  function staticPageLink(request, pages) {
    var path = request.attrs.path;
    if (path == null || !String(path).trim()) return false;
    var target = String(path).split(/[?#]/)[0];
    if (!isStaticPage(target)) return false;
    var file = request.location && request.location.file != null ? String(request.location.file) : null;
    if (file != null && isStaticPage(file)) return true;
    var segments = [];
    if (target.charAt(0) !== '/' && file != null && file.indexOf('/') >= 0) segments = file.substring(0, file.lastIndexOf('/')).split('/');
    var relative = segments.length > 0;
    target.split('/').forEach(function (seg) {
      if (seg === '..') segments.pop();
      else if (seg && seg !== '.') segments.push(seg);
    });
    if (pages.has('/' + segments.join('/'))) return true;
    if (!relative) return false;
    return pages.has('/' + target.split('/').filter(function (seg) { return seg && seg !== '.' && seg !== '..'; }).join('/'));
  }

  /**
   * 화면 요청 통계(report.md의 "화면 요청", F-015-R47·R54). 서버 요청이 아닌 것 셋을 분모에서 뺀다: 외부 URL(external=true), 정적 페이지 링크
   * (화면 시안의 .html 링크), 클라이언트 라우트(clientRoute=true). 서버 요청은 엔드포인트에 연결된 것(확정·추론)과 미연결로 나눈다.
   * stats: { total, server, matched, sure, inferred, unmatched, external, statics, clientRoutes, rate(%, 서버 요청이 없으면 null), summaryText, noteText }
   * rows: 리포트의 표와 같은 순서의 [{ type:'REQUEST_STAT', label, count, description }]. 요청이 없으면 빈 배열.
   * unmatched: 미연결 요청(위치순) [{ type:'REQUEST_UNMATCHED', id, nodeId, label(표준 산출물의 표기), location }]
   */
  function requestStatistics(index) {
    var stats = { total: 0, server: 0, matched: 0, sure: 0, inferred: 0, unmatched: 0, external: 0, statics: 0, clientRoutes: 0, rate: null, summaryText: '', noteText: '' };
    var pages = new Set();
    ['JSP_PAGE', 'FILE'].forEach(function (kind) {
      index.nodesOfKind(kind).forEach(function (n) {
        var path = G.idRemainder(n.id);
        if (!isStaticPage(path)) return;
        for (var slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) pages.add(path.substring(slash));
        pages.add('/' + path);
      });
    });
    var unmatched = [];
    index.nodesOfKind('HTTP_REQUEST').forEach(function (r) {
      stats.total++;
      if (r.attrs.clientRoute === true) { stats.clientRoutes++; return; }
      var linked = false;
      var sure = false;
      index.out(r.id).forEach(function (e) {
        var to = index.node(e.to);
        if (e.kind !== 'MATCHES' || e.confidence === 'UNRESOLVED' || !to || to.kind !== 'HTTP_ENDPOINT') return;
        linked = true;
        // 다른 모듈의 핸들러와 맞춘 대응(crossModule)은 콜트리와 같이 추론으로 본다.
        if ((e.confidence || 'RESOLVED') === 'RESOLVED' && !(e.attrs && e.attrs.crossModule === true)) sure = true;
      });
      if (linked) { if (sure) stats.sure++; else stats.inferred++; }
      else if (r.attrs.external === true) stats.external++;
      else if (staticPageLink(r, pages)) stats.statics++;
      else unmatched.push(r);
    });
    stats.matched = stats.sure + stats.inferred;
    stats.unmatched = unmatched.length;
    stats.server = stats.matched + stats.unmatched;
    if (stats.total) {
      stats.summaryText = '서버 요청 ' + stats.server + '건 중 연결 ' + stats.matched + '(확정 ' + stats.sure + ', 추론 ' + stats.inferred + '), 미연결 ' + stats.unmatched + '.';
      if (stats.server > 0) {
        stats.rate = Math.round(stats.matched * 100 / stats.server);
        stats.summaryText += ' 엔드포인트 연결률 ' + stats.rate + '%.';
      }
      var excluded = [];
      if (stats.external > 0) excluded.push('외부 URL');
      if (stats.statics > 0) excluded.push('정적 페이지 링크(프로젝트의 정적 `.html` 파일을 가리키거나 정적 페이지에서 나가는 `.html` 링크)');
      if (stats.clientRoutes > 0) excluded.push('클라이언트 라우트');
      if (excluded.length) stats.noteText = excluded.join(', ') + '는 서버 요청이 아니라서 분모에서 뺐다.';
    }
    var rows = !stats.total ? [] : [
      { label: '서버 요청', count: stats.server, description: '프로젝트의 엔드포인트로 가야 하는 요청(외부 URL, 정적 페이지 링크, 클라이언트 라우트가 아닌 것)' },
      { label: '연결됨(확정)', count: stats.sure, description: 'MATCHES가 확정(RESOLVED)으로 엔드포인트에 이어진 서버 요청' },
      { label: '연결됨(추론)', count: stats.inferred, description: '확정 대응 없이 추론으로만 이어진 요청(컨텍스트 경로·접두사를 떼고 맞춤, 상대 URL, 동적 조각, 다른 모듈의 핸들러 등). 콜트리에서 추론(~)으로 보인다' },
      { label: '미연결', count: stats.unmatched, description: '맞는 엔드포인트를 찾지 못한 서버 요청. 아래에 위치와 함께 나온다("미해결 참조"의 http-endpoint 분류에도 있다)' },
      { label: '외부 URL', count: stats.external, description: 'external=true: 프로젝트 밖(다른 호스트)으로 가는 요청. 엔드포인트 대상이 아니라서 분모에서 뺀다' },
      { label: '정적 페이지 링크', count: stats.statics, description: '프로젝트의 정적 .html 파일을 가리키거나 정적 페이지(화면 시안)에서 나가는 .html 링크. 서버 요청이 아니라서 분모에서 뺀다' },
      { label: '화면 내 이동(클라이언트 라우트)', count: stats.clientRoutes, description: 'clientRoute=true: 서버 요청이 아니라 화면 안의 이동(React Router 등). 미해결과 분모에서 뺀다' }
    ];
    rows.forEach(function (row) { row.type = 'REQUEST_STAT'; });
    unmatched.sort(function (x, y) {
      var fx = x.location && x.location.file != null ? String(x.location.file) : '';
      var fy = y.location && y.location.file != null ? String(y.location.file) : '';
      return G.compareText(fx, fy) || ((x.location && x.location.startLine) || 0) - ((y.location && y.location.startLine) || 0) || G.compareText(x.id, y.id);
    });
    return { stats: stats, rows: rows, unmatched: unmatched.map(function (r) {
      var method = G.requestMethod(r.attrs);
      var path = r.attrs.path == null || !String(r.attrs.path).trim() ? r.attrs.url : r.attrs.path;
      var label = r.attrs.selfSubmit === true ? G.requestLabel(r) : path != null && String(path).trim() ? (method ? method + ' ' : '') + String(path).replace(/\s+/g, ' ').trim() : G.idRemainder(r.id).replace(/@[^@]*$/, '');
      return { type: 'REQUEST_UNMATCHED', id: r.id, nodeId: r.id, label: label, location: G.lineLocation(r.location) };
    }) };
  }

  /**
   * 다른 방언 파일에서 보충한 구문(SQL_STATEMENT의 borrowedFrom, report.md의 "다른 방언 파일에서 보충한 구문"): 구문 ID 순.
   * [{ type:'BORROWED', id, nodeId, statementLabel(ID의 나머지), borrowedFrom, location }]
   */
  function borrowedStatements(index) {
    // 데이터 적재 묶음의 borrowedFrom(다른 방언 파일의 묶음)은 "데이터 적재 스크립트"에서 밝힌다: 매퍼 구문이 아니다.
    return index.nodesOfKind('SQL_STATEMENT').filter(function (n) { return n.attrs.dataLoad !== true && n.attrs.borrowedFrom != null && String(n.attrs.borrowedFrom).trim(); })
      .sort(function (x, y) { return G.compareText(x.id, y.id); })
      .map(function (n) { return { type: 'BORROWED', id: n.id, nodeId: n.id, statementLabel: G.idRemainder(n.id), borrowedFrom: String(n.attrs.borrowedFrom), location: G.lineLocation(n.location) }; });
  }

  /**
   * DDL에 없는 테이블(declared=false인 DB_TABLE, ID 순)과 그것을 참조하는 구문. 구문 수에서 데이터 적재 구문은 뺀다(report.md의 기본값과 같다).
   * 참조하는 구문이 하나뿐이면 의심으로 표시한다. 적재 구문만 참조하면 그렇게 적는다.
   * [{ type:'UNDECLARED_TABLE', id, nodeId, name, statementCount, statements: [{ id, label }](5개까지), note, suspicious }]
   */
  function undeclaredTables(index, loads) {
    var undeclared = index.nodesOfKind('DB_TABLE').filter(function (n) { return n.attrs.declared === false; });
    if (!undeclared.length) return [];
    var wanted = new Set(undeclared.map(function (n) { return n.id; }));
    var statementsByTable = new Map();
    var add = function (tableId, e) {
      if (loads.ids.has(e.from)) return;
      var set = statementsByTable.get(tableId);
      if (!set) statementsByTable.set(tableId, set = new Set());
      set.add(e.from);
    };
    undeclared.forEach(function (t) {
      index.in(t.id).forEach(function (e) { if (e.kind === 'READS' || e.kind === 'WRITES') add(t.id, e); });
      index.columnsOf(t.id).forEach(function (c) {
        index.in(c.id).forEach(function (e) { if (e.kind === 'READS' || e.kind === 'WRITES') add(t.id, e); });
      });
    });
    // DB가 제공하는 시스템 카탈로그(systemCatalog=true: Oracle 데이터 사전, DB2 카탈로그, PostgreSQL pg_catalog·information_schema)는 뒤에 따로 묶고 의심으로 치지 않는다(report.md와 같다).
    var catalog = function (t) { return t.attrs.systemCatalog === true; };
    return undeclared.slice().sort(function (a, b) { return (catalog(a) ? 1 : 0) - (catalog(b) ? 1 : 0) || G.compareText(a.id, b.id); }).map(function (t) {
      var statements = Array.from(statementsByTable.get(t.id) || []).sort(G.compareText);
      var note = catalog(t) ? SYSTEM_CATALOG : statements.length === 1 ? SUSPICIOUS_TABLE : !statements.length && loads.tables.has(t.id) && wanted.has(t.id) ? DATA_LOAD_ONLY : '';
      return {
        type: 'UNDECLARED_TABLE', id: t.id, nodeId: t.id, name: G.idRemainder(t.id), statementCount: statements.length,
        statements: statements.slice(0, 5).map(function (id) { return { id: id, label: statementName(index, id) }; }),
        note: note, suspicious: !catalog(t) && statements.length === 1, systemCatalog: catalog(t)
      };
    });
  }

  /**
   * 진단의 위치를 사람이 읽는 말로. 위치가 없는 진단(분석 한 번에 1건인 요약)은 "프로젝트 전체", 줄 번호 없이 폴더를 가리키는 진단
   * (벤더 폴더마다 1건인 FILE_SKIPPED, 기본 제외 디렉터리)은 `경로/ (폴더)`, 그 밖에는 `파일[:줄]`이다.
   */
  function whereOf(index, d) {
    var loc = d.location;
    if (!loc || !loc.file) return WHOLE_PROJECT;
    if (loc.startLine > 0) return G.formatLocation(loc);
    return isDirectory(index, String(loc.file)) ? String(loc.file).replace(/\/+$/, '') + '/ (폴더)' : String(loc.file);
  }

  /** 경로가 폴더인지. 스캔한 파일(FILE 노드)이면 파일, 스캔한 파일의 상위 경로면 폴더, 둘 다 아니면 마지막 조각에 확장자가 없을 때 폴더로 본다. */
  function isDirectory(index, path) {
    if (index.node('file:' + path)) return false;
    if (!index._directories) {
      var dirs = new Set();
      index.nodesOfKind('FILE').forEach(function (n) {
        var p = G.idRemainder(n.id);
        for (var slash = p.lastIndexOf('/'); slash > 0; slash = p.lastIndexOf('/', slash - 1)) {
          var dir = p.substring(0, slash);
          if (dirs.has(dir)) break;
          dirs.add(dir);
        }
      });
      index._directories = dirs;
    }
    var clean = path.replace(/\/+$/, '');
    if (index._directories.has(clean)) return true;
    var last = clean.substring(clean.lastIndexOf('/') + 1);
    return last.indexOf('.', 1) < 0;
  }

  function locationKey(loc) {
    return loc && loc.file ? loc.file + ':' + (loc.startLine || 0) : null;
  }

  /**
   * 진단과 미해결 참조를 따로 모은다. 같은 미해결 참조가 진단·노드·엣지로 세 번 나오지 않게, 미해결 참조는 엣지 하나를 한 건으로 센다
   * (report.md의 "미해결 참조"와 같은 셈법). 엣지가 하나도 없는 미해결 노드는 그 노드가 한 건이다.
   * 결과:
   *   diagnostics: [{type:'DIAGNOSTIC', severity, code, message, location, where(읽는 말로 적은 위치), nodeId, unresolved}] 심각도 순.
   *     unresolved는 같은 지점의 미해결 참조 행(없으면 null)이고, 그때 nodeId는 그 참조한 노드다.
   *   unresolved: [{type:'UNRESOLVED', category, categoryLabel, edgeKind, confidence, fromId, fromLabel, targetId, target, location, nodeId, edge}] 분류 순.
   *     nodeId는 눌렀을 때 이동할 노드(참조한 곳, 엣지가 없으면 미해결 노드)다.
   *   codes: [{code, count, description}] 진단 코드별 개수와 그 코드가 뜻하는 것. categories: [{category, label, count}] 미해결 분류별 개수.
   *   requestStats: { total, server, matched, inferred, unmatched, external, clientRoutes, rate, rateText } 화면 요청 통계(외부 URL과 클라이언트 라우트는
   *     미해결과 연결률에서 뺀다). requestRows: 그 통계를 report.md의 표와 같은 순서로 적은 행.
   *   columnMismatches: DDL에 없는데 SQL이 참조하는 컬럼(report.md의 "SQL과 DDL의 컬럼 불일치").
   *   dataLoadSummary·dataLoadFiles: 데이터 적재 스크립트의 요약과 파일별 행. undeclaredTables: DDL에 없는 테이블(의심 표시 포함).
   */
  function collect(index) {
    var unresolved = [];
    var seenEdges = new Set();
    // 클라이언트 라우트(clientRoute=true: 화면 안의 이동)는 엔드포인트 대상이 아니다. 맞는 엔드포인트가 없어도 미해결로 세지 않는다(report.md와 같다).
    var isRoute = function (id) { var n = index.node(id); return !!n && n.kind === 'HTTP_REQUEST' && n.attrs.clientRoute === true; };
    var resolvedAnotherWay = function (id) { var n = index.node(id); return !!n && n.kind === 'UNRESOLVED' && n.attrs.resolvedBy != null; };
    // 신뢰도 UNRESOLVED 엣지 수(그래프 그대로)와 이 목록의 건수가 다른 까닭을 센다(report.md의 "미해결 참조" 머리 문장과 같다, F-015-R73).
    var counts = { unresolvedEdges: 0, resolvedElsewhere: 0, clientRoutes: 0, extra: 0, resolvedBy: new Set() };
    function addEdge(e) {
      if (seenEdges.has(e)) return;
      seenEdges.add(e);
      var unresolvedConfidence = e.confidence === 'UNRESOLVED';
      if (e.kind === 'MATCHES' && isRoute(e.from)) { if (unresolvedConfidence) counts.clientRoutes++; return; }
      // 같은 지점에서 다른 방법(접두 일치 등)으로 풀린 참조(미해결 노드의 resolvedBy)는 미해결이 아니다(report.md와 같다).
      if (resolvedAnotherWay(e.to)) {
        if (unresolvedConfidence) counts.resolvedElsewhere++;
        counts.resolvedBy.add(String(index.node(e.to).attrs.resolvedBy));
        return;
      }
      if (!unresolvedConfidence) counts.extra++;
      unresolved.push(unresolvedRow(index, e));
    }
    index.edges.forEach(function (e) { if (e.confidence === 'UNRESOLVED') { counts.unresolvedEdges++; addEdge(e); } });
    index.nodesOfKind('UNRESOLVED').forEach(function (n) {
      var touching = index.in(n.id).concat(index.out(n.id));
      if (touching.length) { touching.forEach(addEdge); return; }
      if (n.attrs.resolvedBy != null) return;
      var d = describe(n, n.id);
      unresolved.push({
        type: 'UNRESOLVED', category: d.category, categoryLabel: d.label, edgeKind: '', confidence: 'UNRESOLVED',
        fromId: null, fromLabel: '', targetId: n.id, target: d.text,
        location: G.formatLocation(n.location), nodeId: n.id, edge: null
      });
    });
    unresolved.forEach(function (r, i) { r._order = i; });
    unresolved.sort(function (a, b) {
      return G.compareText(a.category, b.category) || G.compareText(a.location || '', b.location || '') || a._order - b._order;
    });

    var byLocation = new Map();
    unresolved.forEach(function (r) {
      var key = locationKey(r.edge ? r.edge.location : null) || locationKey((index.node(r.targetId) || {}).location);
      if (key && !byLocation.has(key)) byLocation.set(key, r);
    });

    var diagnostics = index.diagnostics.map(function (d, i) {
      var related = REFERENCE_CODES[d.code] ? byLocation.get(locationKey(d.location)) || null : null;
      return {
        type: 'DIAGNOSTIC', severity: d.severity || 'INFO', code: d.code || '', message: d.message || '',
        location: G.formatLocation(d.location), where: whereOf(index, d), nodeId: related ? related.nodeId : null, unresolved: related, _order: i
      };
    });
    diagnostics.sort(function (a, b) {
      var sa = SEVERITY_RANK[a.severity] == null ? 3 : SEVERITY_RANK[a.severity];
      var sb = SEVERITY_RANK[b.severity] == null ? 3 : SEVERITY_RANK[b.severity];
      return sa - sb || a._order - b._order;
    });

    var codeCounts = new Map();
    diagnostics.forEach(function (r) { codeCounts.set(r.code, (codeCounts.get(r.code) || 0) + 1); });
    var codes = [];
    codeCounts.forEach(function (count, code) { codes.push({ code: code, count: count, description: codeDescription(code) }); });
    codes.sort(function (a, b) { return G.compareText(a.code, b.code); });

    var categoryCounts = new Map();
    unresolved.forEach(function (r) {
      var c = categoryCounts.get(r.category);
      if (!c) categoryCounts.set(r.category, c = { category: r.category, label: r.categoryLabel, count: 0 });
      c.count++;
    });
    var categories = Array.from(categoryCounts.values());
    categories.sort(function (a, b) { return G.compareText(a.category, b.category); });

    var dynamic = CA.crud.dynamicStatements(index).map(function (d) {
      return { type: 'DYNAMIC', id: d.id, nodeId: d.id, label: d.label, access: d.access, location: d.location, text: d.text };
    });
    var loads = dataLoads(index);
    var undeclared = undeclaredTables(index, loads);
    var requests = requestStatistics(index);
    return {
      diagnostics: diagnostics, unresolved: unresolved, unresolvedDifference: unresolvedDifference(counts),
      codes: codes, categories: categories, requestStats: requests.stats, requestRows: requests.rows,
      unmatchedRequests: requests.unmatched, borrowedStatements: borrowedStatements(index),
      columnMismatches: columnMismatches(index), undeclaredCatalogs: undeclared.filter(function (t) { return t.systemCatalog; }).length,
      uncalledStatements: uncalledStatements(index), dynamicStatements: dynamic,
      dataLoadSummary: loads.summary, dataLoadFiles: loads.files,
      undeclaredTables: undeclared, undeclaredSuspicious: undeclared.filter(function (t) { return t.suspicious; }).length
    };
  }

  /**
   * 미해결 참조의 건수가 신뢰도 UNRESOLVED 엣지 수와 다른 까닭(표준 산출물 SummaryReport.unresolvedDifference와 같은 문장). 같으면 ''.
   * 같은 지점에서 접두 일치로 풀린 참조(대상 노드의 resolvedBy)는 뺐고, 클라이언트 라우트의 대응은 "화면 요청"에서 세며,
   * 신뢰도는 UNRESOLVED가 아니지만 대상이 미해결 노드인 엣지는 더했다.
   */
  function unresolvedDifference(counts) {
    var sentences = [];
    if (counts.resolvedElsewhere > 0 || counts.clientRoutes > 0) {
      var parts = [];
      if (counts.resolvedElsewhere > 0) {
        var how = counts.resolvedBy.size === 1 && counts.resolvedBy.has('prefixMatch') ? '접두 일치로' : '다른 방법으로';
        parts.push(counts.resolvedElsewhere + '건은 같은 지점에서 ' + how + ' 풀려(대상 노드의 `resolvedBy`) 여기서 뺐다');
      }
      if (counts.clientRoutes > 0) parts.push(counts.clientRoutes + '건은 클라이언트 라우트의 대응이라 "화면 요청" 절에서 센다');
      sentences.push('신뢰도가 `UNRESOLVED`인 엣지 ' + counts.unresolvedEdges + '건 가운데 ' + parts.join(', ') + '.');
    }
    if (counts.extra > 0) sentences.push('신뢰도는 `UNRESOLVED`가 아니지만 대상이 미해결 노드인 엣지 ' + counts.extra + '건을 더했다.');
    return sentences.join(' ');
  }

  function haystack(r) {
    if (r.type === 'UNRESOLVED') return [r.fromLabel, r.edgeKind, r.target, r.note || '', r.location || '', r.categoryLabel].join(' ');
    if (r.type === 'UNCALLED') return [r.statementLabel, r.location || ''].concat(r.executors.map(function (m) { return m.label; })).join(' ');
    if (r.type === 'DYNAMIC') return [r.id, r.label, r.access, r.location || '', r.text || ''].join(' ');
    if (r.type === 'UNDECLARED_TABLE') return [r.name, r.note].concat(r.statements.map(function (x) { return x.label; })).join(' ');
    if (r.type === 'DATALOAD') return [r.file, r.rowsText, r.borrowedNote || ''].concat(r.tables).join(' ');
    if (r.type === 'COLUMN_MISMATCH') return [r.table, r.column].concat(r.statements.map(function (x) { return x.label; })).join(' ');
    if (r.type === 'REQUEST_STAT') return [r.label, r.description].join(' ');
    if (r.type === 'REQUEST_UNMATCHED') return [r.label, r.location || ''].join(' ');
    if (r.type === 'BORROWED') return [r.statementLabel, r.borrowedFrom, r.location || ''].join(' ');
    return [r.message, r.where || r.location || '', r.code].join(' ');
  }

  /**
   * 진단 행이나 미해결 행을 거른다. 비어 있는 조건은 거르지 않는다.
   * f.codes, f.severities: 진단의 코드와 심각도. f.categories: 미해결 분류. f.text: 메시지·대상·참조한 곳·위치에서 찾는다(공백으로 여러 낱말).
   */
  function filter(rows, f) {
    var o = f || {};
    var codes = o.codes && o.codes.length ? new Set(o.codes) : null;
    var severities = o.severities && o.severities.length ? new Set(o.severities) : null;
    var categories = o.categories && o.categories.length ? new Set(o.categories) : null;
    var text = String(o.text || '').trim().toLowerCase();
    var tokens = text ? text.split(/\s+/) : [];
    return rows.filter(function (r) {
      if (codes && !codes.has(r.code)) return false;
      if (severities && !severities.has(r.severity)) return false;
      if (categories && !categories.has(r.category)) return false;
      if (tokens.length) {
        var hay = haystack(r).toLowerCase();
        for (var i = 0; i < tokens.length; i++) if (hay.indexOf(tokens[i]) < 0) return false;
      }
      return true;
    });
  }

  var api = { VIEWS: VIEWS, viewCount: viewCount, collect: collect, filter: filter, codeDescription: codeDescription, dataLoads: dataLoads,
    columnMismatches: columnMismatches, requestStatistics: requestStatistics };
  CA.diagnostics = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
