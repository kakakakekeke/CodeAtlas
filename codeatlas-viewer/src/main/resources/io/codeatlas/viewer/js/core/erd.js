// ERD: 범위 선택과 Mermaid 소스 생성. DOM을 쓰지 않는다.
(function (root) {
  'use strict';
  if (typeof module === 'object' && module.exports) require('./graph.js');
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var G = CA.graph;

  var DEFAULT_MAX_NODES = 60;
  var MAX_COMMENT = 60;
  var REFERENCED_COLUMN = '참조된 컬럼';
  var OUTSIDE_AREA = '다른 영역';

  /**
   * 범례. line은 관계 선의 모양, box는 테이블 테두리의 모양.
   * ends는 선 끝의 카디널리티 표시: true(부모 쪽 하나·자식 쪽 여럿처럼 정해진 표시), 'optional'(부모 쪽 0 또는 1·자식 쪽 여럿: 추론 관계), false(표시 없음),
   * 'weak'(양쪽 다 가장 약한 표시 `}o`·`o{`: 0개 이상).
   */
  var LEGEND = [
    { key: 'ddl', label: 'DDL 외래 키', description: 'DDL에 선언된 외래 키 (REFERENCES, source=DDL)', line: 'solid', ends: true, box: null },
    { key: 'inferred', label: '추론 관계', description: "SQL의 조인 조건에서 추론한 관계 (source=INFERRED). 제약이 없어 부모가 반드시 있는지 알 수 없으므로 부모 쪽을 '0 또는 1'로 그린다. 괄호 안의 '추론 n건'은 근거가 된 구문 수. GROUP_CD='…'처럼 적힌 것은 부모의 복합 키 가운데 상수로 고정한 컬럼이다(공통코드 테이블). '(upgrade 스크립트, 현재 정의에 없음)'은 upgrade 스크립트의 ALTER TABLE에만 있고 현재 정의(전체 DDL)에는 없는 외래 키다", line: 'dashed', ends: 'optional', box: null },
    { key: 'undirected', label: '추론 관계 (방향 미상)', description: '어느 쪽이 부모인지 정하지 못한 추론 관계 (directionKnown=false). 선 끝에 카디널리티 표시가 없다. 까닭은 셋이다: 선언된 FK와 반대(DDL의 외래 키와 방향이 어긋나 거둠), 유일하지 않은 키(부모로 본 쪽의 키가 유일하지 않아 거둠), 키 정보 없음(어느 쪽에도 PK·UNIQUE가 없어 정하지 못함). 앞의 둘은 라벨에, 셋 다 관계 상세(REFERENCES 엣지)에 적힌다', line: 'dashed', ends: false, box: null },
    { key: 'cardinalityUnknown', label: '추론 관계 (카디널리티 모름)', description: "방향(자식 → 부모)만 알고 카디널리티는 정하지 못한 추론 관계 (directionKnown=true, directionFrom=DDL, cardinality=unknown). SQL의 조인만으로는 어느 쪽이 부모인지 정하지 못해 같은 테이블 쌍의 DDL 외래 키 방향을 따랐다: 라벨에 '방향은 DDL, 카디널리티 모름'이 붙는다. 부모 쪽 조인 컬럼이 유일한지 모르므로 N:1로 그리지 않고 양쪽 끝을 가장 약한 표시(0개 이상)로 둔다. N:M이라는 뜻이 아니다", line: 'dashed', ends: 'weak', box: null },
    { key: 'undeclared', label: 'DDL 없음', description: 'SQL에서 참조했지만 DDL에 정의가 없는 테이블 (declared=false). 참조된 컬럼만 보인다', line: null, ends: false, box: 'dashed' }
  ];

  /** 방향을 거둔 까닭(표준 산출물의 ErdLabels.directionNote와 같은 문구). 없으면 ''. */
  function directionNoteOf(attrs) {
    var a = attrs || {};
    return a.declaredOpposite === true ? '선언된 FK와 반대' : a.pseudoKey === true ? '유일하지 않은 키' : '';
  }

  function relationOf(e) {
    var a = e.attrs || {};
    var source = a.source || (e.confidence === 'INFERRED' ? 'INFERRED' : 'DDL');
    // upgrade 스크립트에만 있고 현재 정의에 없는 외래 키는 fromUpgrade=true이면서 INFERRED로 온다(source가 DDL이어도 엣지 신뢰도가 INFERRED면
    // 추론 관계로 본다): 점선으로 그리고 라벨에 `(upgrade 스크립트, 현재 정의에 없음)`을 적는다(F-013-R26).
    if (a.fromUpgrade === true && e.confidence === 'INFERRED') source = 'INFERRED';
    return {
      from: e.from, to: e.to,
      source: source,
      columns: Array.isArray(a.columns) ? a.columns : [],
      refColumns: Array.isArray(a.refColumns) ? a.refColumns : [],
      constraint: a.constraint || '',
      evidenceCount: a.evidenceCount,
      // 상수로 채운 복합 키(F-018-R9): 부모 키에서 조인 컬럼을 뺀 나머지와 근거 구문에 나온 `COL=값`. 공통코드 테이블 조인이 이 꼴이다.
      fixedColumns: Array.isArray(a.fixedColumns) ? a.fixedColumns : [],
      fixedValues: Array.isArray(a.fixedValues) ? a.fixedValues : [],
      // 추론 관계만 방향을 모를 수 있다. directionKnown=false면 from→to에 뜻이 없다(계약 design 3.5 공통 규칙).
      directionKnown: !(source === 'INFERRED' && a.directionKnown === false),
      // 방향을 정했다가 거둔 까닭(링커의 declaredOpposite·pseudoKey). 없으면 ''.
      directionNote: directionNoteOf(a),
      // upgrade·patch 스크립트의 ALTER TABLE에서 온 외래 키(DDL 관계로 그리되 라벨로 구분한다, F-013-R20).
      // source가 INFERRED면 현재 정의에 없는 것이다(점선, F-013-R26).
      fromUpgrade: a.fromUpgrade === true,
      cardinality: a.cardinality || '',
      // 방향(자식 → 부모)은 알지만 카디널리티는 링커가 정하지 못한 추론 관계(F-013-R27). 끝 표시로 N:1을 단정하지 않는다.
      cardinalityUnknown: source === 'INFERRED' && a.directionKnown !== false && isUnknownCardinality(a.cardinality),
      // 조인만으로는 방향을 정하지 못해 따른 근거(링커의 directionFrom. 값 DDL: 같은 테이블 쌍의 DDL 외래 키 방향). 없으면 ''.
      directionFrom: source === 'INFERRED' && a.directionKnown !== false && !isBlank(a.directionFrom) ? String(a.directionFrom).trim() : '',
      edge: e
    };
  }

  /** 엣지의 cardinality가 링커가 "모름"이라고 적은 값(unknown)인가. 없는 것(옛 그래프)은 모름이 아니라 "적지 않음"이다. */
  function isUnknownCardinality(value) {
    return typeof value === 'string' && value.trim().toLowerCase() === 'unknown';
  }

  var CARDINALITY_UNKNOWN = '카디널리티 모름';
  var DIRECTION_FROM_DDL = '방향은 DDL';

  /** 방향만 아는 추론 관계의 라벨에 붙이는 말(ErdLabels.inferredNote): `방향은 DDL, 카디널리티 모름`. 방향의 근거가 DDL이 아니면 뒤의 것만. */
  function cardinalityUnknownNote(directionFrom) {
    return (String(directionFrom || '').toUpperCase() === 'DDL' ? DIRECTION_FROM_DDL + ', ' : '') + CARDINALITY_UNKNOWN;
  }

  /**
   * opts.tables: 테이블 노드 ID 배열. 없거나 비면 모든 테이블.
   * opts.depth: 선택한 테이블에서 REFERENCES를 따라(양방향) 넓힐 단계 수(기본 1).
   * opts.includeInferred: 추론 관계를 넣는다(기본 true). opts.includeViews: 뷰(DB_VIEW)도 넣는다(기본 false).
   * opts.maxNodes: 테이블 수 상한.
   * 결과: { tables, relations({from(자식), to(부모), source, columns, refColumns, constraint, evidenceCount, directionKnown, cardinality,
   *        cardinalityUnknown(방향은 알고 카디널리티는 모르는 추론 관계), directionFrom}), total, truncated }
   */
  function selectScope(index, opts) {
    var o = opts || {};
    var includeInferred = o.includeInferred !== false;
    var includeViews = o.includeViews === true;
    var max = o.maxNodes > 0 ? o.maxNodes : DEFAULT_MAX_NODES;
    var depth = o.depth == null ? 1 : o.depth;
    var wanted = function (e) {
      if (e.kind !== 'REFERENCES') return false;
      return includeInferred || relationOf(e).source !== 'INFERRED';
    };
    // DB가 제공하는 시스템 카탈로그(systemCatalog=true: 데이터 사전)는 프로젝트 스키마가 아니다: 그리지 않고 수만 알린다(F-013-R29).
    var isEntity = function (n) { return !!n && (n.kind === 'DB_TABLE' || (includeViews && n.kind === 'DB_VIEW')) && !isSystemCatalog(n); };
    var catalogs = systemCatalogCount(index, includeViews);

    var picked = [];
    var seen = new Set();
    function pick(node) {
      if (!isEntity(node) || seen.has(node.id)) return false;
      seen.add(node.id);
      picked.push(node);
      return true;
    }

    if (o.area != null) {
      // 영역(테이블 이름 접두사 묶음): 그 영역의 테이블과, 관계로 이어진 다른 영역의 테이블(이름만 그린다), 한쪽 끝이라도 영역 안인 관계.
      // 영역의 크기는 글자 수 한도로 이미 묶여 있으므로 테이블 수 상한을 다시 걸지 않는다.
      var scoped = areaScope(index, o);
      scoped.systemCatalogs = catalogs;
      return scoped;
    }
    if (o.tables && o.tables.length) {
      var frontier = [];
      o.tables.forEach(function (id) { var n = index.node(id); if (pick(n)) frontier.push(n); });
      for (var d = 0; d < depth && frontier.length; d++) {
        var next = [];
        frontier.forEach(function (t) {
          index.out(t.id).forEach(function (e) { if (wanted(e)) { var n = index.node(e.to); if (pick(n)) next.push(n); } });
          index.in(t.id).forEach(function (e) { if (wanted(e)) { var n = index.node(e.from); if (pick(n)) next.push(n); } });
        });
        frontier = next;
      }
    } else {
      var all = index.nodesOfKind('DB_TABLE').slice();
      if (includeViews) all = all.concat(index.nodesOfKind('DB_VIEW'));
      all.sort(function (a, b) { return G.compareText(a.name, b.name) || G.compareText(a.id, b.id); }).forEach(pick);
    }

    var total = picked.length;
    var tables = total > max ? picked.slice(0, max) : picked;
    var inScope = new Set(tables.map(function (t) { return t.id; }));
    var relations = [];
    var declaredKeys = new Set();
    tables.forEach(function (t) {
      index.out(t.id).forEach(function (e) {
        if (!wanted(e) || !inScope.has(e.to)) return;
        var r = relationOf(e);
        relations.push(r);
        if (r.source !== 'INFERRED') declaredKeys.add(r.from + '\u0000' + r.to + '\u0000' + r.columns.join(','));
      });
    });
    // 같은 컬럼의 관계가 DDL에도 있으면 추론 쪽은 뺀다.
    relations = relations.filter(function (r) {
      return r.source !== 'INFERRED' || !declaredKeys.has(r.from + '\u0000' + r.to + '\u0000' + r.columns.join(','));
    });
    return { tables: tables, relations: relations, total: total, truncated: total > max, systemCatalogs: catalogs };
  }

  function isSystemCatalog(node) {
    return !!node && !!node.attrs && node.attrs.systemCatalog === true;
  }

  /** ERD에서 뺀 시스템 카탈로그 테이블(뷰를 넣으면 뷰도)의 수. */
  function systemCatalogCount(index, includeViews) {
    var count = index.nodesOfKind('DB_TABLE').filter(isSystemCatalog).length;
    if (includeViews) count += index.nodesOfKind('DB_VIEW').filter(isSystemCatalog).length;
    return count;
  }

  // ---------- 표준 산출물의 ERD 모델과 Mermaid 글자 (codeatlas-export의 ErdModelBuilder, MermaidErdRenderer)
  //
  // 표준 산출물은 전체 ERD의 Mermaid 텍스트가 한도(export.mermaid.maxChars, 기본 45,000자)를 넘으면 주제 영역별로 나눈다(erd/index.md).
  // 영역의 경계는 "표준 산출물이 그린 Mermaid의 글자 수"로 정해지므로, 같은 영역 목록을 내려면 그 글자를 똑같이 만들어야 한다.
  // 화면에 그리는 Mermaid(toMermaid)와는 표기가 조금 다르다(들여쓰기, 주석 줄, NOT NULL 표시). 여기 것은 영역을 나누는 데만 쓴다.

  var DEFAULT_MAX_CHARS = 45000;
  var MIN_AREA_SIZE = 3;
  var ETC = '기타';
  // 접두사로 묶이지 않은 테이블 가운데 전체 테이블의 이 비율(%)을 넘는 테이블이 참조하는 것은 허브다(ErdSplitter.HUB_PERCENT).
  var HUB_PERCENT = 20;
  // 관계로 묶은 영역과 이름 접두 군집 하나의 테이블 수 상한(ErdSplitter.MAX_AREA_TABLES). 접두사 영역과 기타는 글자 수 한도만 본다.
  var MAX_AREA_TABLES = 30;
  // 이름 접두 군집의 접두사는 이 글자 수 이상이어야 한다(ErdSplitter.MIN_NAME_PREFIX).
  var MIN_NAME_PREFIX = 3;
  // 영역 표시 이름에 넣는 논리명의 글자 수 상한(ErdSplitter.MAX_LABEL_COMMENT).
  var MAX_LABEL_COMMENT = 40;

  function oneLine(text) {
    return text == null ? '' : String(text).replace(/[\r\n\t]+/g, ' ').trim();
  }

  function isBlank(text) {
    return text == null || !String(text).trim();
  }

  function listOf(value) {
    return Array.isArray(value) ? value : typeof value === 'string' ? [value] : [];
  }

  /**
   * 추론 관계(점선)인가(ErdModelBuilder.isInferred): source=INFERRED이거나, source가 없고 엣지 신뢰도가 INFERRED이거나,
   * upgrade 스크립트에서 온 외래 키(fromUpgrade=true)인데 엣지 신뢰도가 INFERRED인 것(현재 정의에 없는 외래 키).
   */
  function isInferredEdge(e) {
    var source = e.attrs ? e.attrs.source : null;
    if (source != null && String(source).toUpperCase() === 'INFERRED') return true;
    return e.confidence === 'INFERRED' && (source == null || (e.attrs && e.attrs.fromUpgrade === true));
  }

  function relationKey(a, b, columnsA, columnsB) {
    return a + '|' + b + '|' + columnsA.join(',') + '|' + columnsB.join(',');
  }

  /**
   * 표준 산출물과 같은 ERD 모델(스키마 전체).
   * opts.includeViews(기본 false), opts.includeInferred(기본 true), opts.minEvidence(기본 1), opts.columns('all' 기본, 'keys', 'none').
   * 결과: { entities: [{ id, name, node, view, declared, comment, columns: [{ name, dataType, nullable, pk, fk, unique, ordinal, comment, referencedOnly }] }] 이름순,
   *        relations: [{ childId, parentId, child, parent, columns, refColumns, constraint, inferred, evidence, directionKnown, childSide, parentSide,
   *                      directionFrom(없으면 null), cardinalityUnknown(방향은 알고 카디널리티는 모르는 추론 관계) }],
   *        systemCatalogs: 뺀 시스템 카탈로그(systemCatalog=true)의 이름(이름순) }
   */
  function exportModel(index, opts) {
    var o = opts || {};
    var includeViews = o.includeViews === true;
    var includeInferred = o.includeInferred !== false;
    var minEvidence = o.minEvidence == null ? 1 : o.minEvidence;
    var level = o.columns || 'all';
    var candidates = new Map();
    var nodes = index.nodesOfKind('DB_TABLE').slice();
    if (includeViews) nodes = nodes.concat(index.nodesOfKind('DB_VIEW'));
    // 시스템 카탈로그(systemCatalog=true)는 엔티티로도 관계의 끝으로도 넣지 않고 이름만 모은다(ErdModelBuilder와 같다, F-013-R29).
    var systemCatalogs = [];
    nodes.sort(function (a, b) { return G.compareText(a.id, b.id); }).forEach(function (n) {
      if (isSystemCatalog(n)) systemCatalogs.push(n.name);
      else candidates.set(n.id, n);
    });
    systemCatalogs.sort(G.compareText);

    // 관계: 같은 테이블 쌍·같은 컬럼의 관계는 한 번만. DDL이 추론보다 먼저다.
    var edges = [];
    candidates.forEach(function (n) {
      index.out(n.id).forEach(function (e) { if (e.kind === 'REFERENCES' && candidates.has(e.to)) edges.push(e); });
    });
    var text = function (e, key) { return listOf(e.attrs ? e.attrs[key] : null).join(','); };
    var constraintOf = function (e) { return e.attrs && e.attrs.constraint != null ? String(e.attrs.constraint) : ''; };
    edges.sort(function (a, b) {
      return G.compareText(a.from, b.from) || G.compareText(a.to, b.to) || G.compareText(text(a, 'columns'), text(b, 'columns'))
        || G.compareText(text(a, 'refColumns'), text(b, 'refColumns')) || G.compareText(constraintOf(a), constraintOf(b))
        || G.compareText(G.lineLocation(a.location) || '', G.lineLocation(b.location) || '');
    });
    var ddl = [];
    var inferred = [];
    edges.forEach(function (e) {
      var a = e.attrs || {};
      var isInferred = isInferredEdge(e);
      var evidence = typeof a.evidenceCount === 'number' ? Math.trunc(a.evidenceCount) : 0;
      var r = { edge: e, childId: e.from, parentId: e.to, columns: listOf(a.columns), refColumns: listOf(a.refColumns), inferred: isInferred,
        evidence: evidence, directionKnown: !isInferred || a.directionKnown !== false, directionNote: directionNoteOf(a),
        fromUpgrade: a.fromUpgrade === true,
        directionFrom: isInferred && a.directionKnown !== false && !isBlank(a.directionFrom) ? String(a.directionFrom).trim() : null };
      if (!isInferred) ddl.push(r);
      // 현재 정의에 없는 upgrade 외래 키는 근거 수로 거르지 않는다(추론한 것이 아니라 스크립트에 적힌 것이다).
      else if (includeInferred && (r.fromUpgrade || Math.max(1, evidence) >= minEvidence)) inferred.push(r);
    });
    var raw = [];
    var seen = new Set();
    ddl.forEach(function (r) {
      var key = relationKey(r.childId, r.parentId, r.columns, r.refColumns) + '|' + (r.edge.attrs && r.edge.attrs.constraint != null ? r.edge.attrs.constraint : 'null');
      if (!seen.has(key)) { seen.add(key); raw.push(r); }
    });
    var pairs = new Set();
    raw.forEach(function (r) {
      pairs.add(relationKey(r.childId, r.parentId, r.columns, r.refColumns));
      pairs.add(relationKey(r.parentId, r.childId, r.refColumns, r.columns));
    });
    inferred.forEach(function (r) {
      var key = relationKey(r.childId, r.parentId, r.columns, r.refColumns);
      if (pairs.has(key)) return;
      pairs.add(key);
      pairs.add(relationKey(r.parentId, r.childId, r.refColumns, r.columns));
      raw.push(r);
    });

    // 정의는 없고(CONTAINS 없음) SQL에서 참조만 된 컬럼: DDL에 없는 테이블에 ID로 붙인다.
    var orphanColumns = new Map();
    var undeclaredByName = new Map();
    candidates.forEach(function (n) {
      if (n.attrs.declared === false && !undeclaredByName.has(G.idRemainder(n.id))) undeclaredByName.set(G.idRemainder(n.id), n.id);
    });
    if (undeclaredByName.size) {
      index.nodesOfKind('DB_COLUMN').forEach(function (column) {
        var rest = G.idRemainder(column.id);
        for (var dot = rest.indexOf('.'); dot > 0; dot = rest.indexOf('.', dot + 1)) {
          var owner = undeclaredByName.get(rest.substring(0, dot));
          if (owner == null) continue;
          var incoming = index.in(column.id);
          var contained = false;
          var accessed = false;
          for (var i = 0; i < incoming.length; i++) {
            if (incoming[i].kind === 'CONTAINS') contained = true;
            else if (incoming[i].kind === 'READS' || incoming[i].kind === 'WRITES') accessed = true;
          }
          if (!contained && accessed) {
            if (!orphanColumns.has(owner)) orphanColumns.set(owner, []);
            orphanColumns.get(owner).push(column);
          }
          break;
        }
      });
    }

    var uniqueKeys = function (entity) { return uniqueKeysOf(entity); };
    var columnName = function (column, ownerPrefix) {
      var rest = G.idRemainder(column.id);
      return rest.indexOf(ownerPrefix) === 0 && rest.length > ownerPrefix.length ? rest.substring(ownerPrefix.length) : column.name;
    };
    var referenced = function (name, dataType) {
      return { name: name, dataType: isBlank(dataType) ? null : String(dataType).trim(), nullable: null, pk: false, fk: false, unique: false, ordinal: 0, comment: null, referencedOnly: true };
    };
    var rawByEntity = new Map();
    raw.forEach(function (r) {
      [r.childId, r.parentId].forEach(function (id) {
        if (!rawByEntity.has(id)) rawByEntity.set(id, []);
        rawByEntity.get(id).push(r);
      });
    });
    var columnsOf = function (entity) {
      var declared = entity.attrs.declared !== false;
      var fkColumns = new Set();
      var byName = new Map();
      var prefix = G.idRemainder(entity.id) + '.';
      index.out(entity.id).forEach(function (e) {
        if (e.kind === 'REFERENCES') {
          if (!isInferredEdge(e)) listOf(e.attrs ? e.attrs.columns : null).forEach(function (c) { fkColumns.add(c); });
        }
      });
      index.out(entity.id).forEach(function (e) {
        if (e.kind !== 'CONTAINS') return;
        var c = index.node(e.to);
        if (!c || c.kind !== 'DB_COLUMN') return;
        // 정의가 있는 테이블의 declared=false 컬럼은 SQL만 아는 컬럼이다(DDL에 없다): ERD에 넣지 않는다(F-013-R19).
        if (entity.attrs.declared !== false && c.attrs.declared === false) return;
        var name = columnName(c, prefix);
        if (byName.has(name)) return;
        var a = c.attrs;
        byName.set(name, { name: name, dataType: isBlank(a.dataType) ? null : String(a.dataType).trim(), nullable: typeof a.nullable === 'boolean' ? a.nullable : null,
          pk: a.primaryKey === true, fk: fkColumns.has(name), unique: a.unique === true, ordinal: typeof a.ordinal === 'number' ? Math.trunc(a.ordinal) : 0,
          comment: a.comment == null ? null : String(a.comment), referencedOnly: !declared });
      });
      if (!declared) {
        (orphanColumns.get(entity.id) || []).forEach(function (c) {
          var name = columnName(c, prefix);
          if (!byName.has(name)) byName.set(name, referenced(name, c.attrs.dataType));
        });
        // 관계에 쓰인 컬럼도 참조된 컬럼이다.
        (rawByEntity.get(entity.id) || []).forEach(function (r) {
          if (r.childId === entity.id) r.columns.forEach(function (n) { if (!byName.has(n)) byName.set(n, referenced(n, null)); });
          if (r.parentId === entity.id) r.refColumns.forEach(function (n) { if (!byName.has(n)) byName.set(n, referenced(n, null)); });
        });
      }
      var tablePk = nameSet(listOf(entity.attrs.primaryKey));
      var singleUnique = new Set();
      uniqueKeys(entity).forEach(function (key) { if (key.length === 1) singleUnique.add(key[0]); });
      var columns = Array.from(byName.values());
      columns.forEach(function (c) {
        if (tablePk.has(c.name)) c.pk = true;
        if (singleUnique.has(c.name)) c.unique = true;
      });
      columns.sort(function (a, b) {
        return (a.ordinal <= 0 ? 2147483647 : a.ordinal) - (b.ordinal <= 0 ? 2147483647 : b.ordinal) || G.compareText(a.name, b.name);
      });
      return columns;
    };

    var full = new Map();
    candidates.forEach(function (n) { full.set(n.id, columnsOf(n)); });
    var side = function (r) {
      if (!r.directionKnown) return ['unknown', 'unknown'];
      // 추론 관계의 cardinality=unknown은 링커가 정하지 못했다는 뜻이다: 자식 컬럼으로 N:1을 계산해 내지 않는다(F-013-R27).
      if (r.inferred && isUnknownCardinality(r.edge.attrs ? r.edge.attrs.cardinality : null)) return ['unknown', 'unknown'];
      var childColumns = full.get(r.childId);
      var byName = new Map();
      childColumns.forEach(function (c) { byName.set(c.name, c); });
      var fk = new Set(r.columns);
      var pk = childColumns.filter(function (c) { return c.pk; }).map(function (c) { return c.name; });
      var unique = fk.size > 0 && ((pk.length > 0 && pk.every(function (n) { return fk.has(n); }))
        || r.columns.some(function (n) { return byName.has(n) && byName.get(n).unique; })
        || uniqueKeys(candidates.get(r.childId)).some(function (key) { return key.every(function (n) { return fk.has(n); }); }));
      var optional = r.columns.some(function (n) { return byName.has(n) && !byName.get(n).pk && byName.get(n).nullable === true; });
      var child = unique ? 'zeroOrOne' : 'many';
      // 추론 관계에는 제약이 없다: 부모가 반드시 있는지 알 수 없으므로 부모 쪽은 0..1이다(F-013-R28).
      var parent = optional || r.inferred ? 'zeroOrOne' : 'one';
      var explicit = parseCardinality(r.edge.attrs ? r.edge.attrs.cardinality : null);
      if (explicit) {
        child = explicit[0] === 'many' ? 'many' : 'zeroOrOne';
        if (explicit[1] === 'zeroOrOne') parent = 'zeroOrOne';
      }
      return [child, parent];
    };
    var relations = raw.map(function (r) {
      var sides = side(r);
      var constraint = r.edge.attrs ? r.edge.attrs.constraint : null;
      return { childId: r.childId, parentId: r.parentId, child: candidates.get(r.childId).name, parent: candidates.get(r.parentId).name,
        columns: r.columns, refColumns: r.refColumns, constraint: isBlank(constraint) ? null : String(constraint), inferred: r.inferred,
        evidence: r.evidence, directionKnown: r.directionKnown, directionNote: r.directionNote, fromUpgrade: r.fromUpgrade, childSide: sides[0], parentSide: sides[1],
        directionFrom: r.directionFrom, cardinalityUnknown: r.directionKnown && sides[0] === 'unknown' };
    });
    relations.sort(function (a, b) {
      return G.compareText(a.child, b.child) || G.compareText(a.parent, b.parent) || G.compareText(a.columns.join(','), b.columns.join(','))
        || G.compareText(a.refColumns.join(','), b.refColumns.join(',')) || G.compareText(a.constraint || '', b.constraint || '')
        || (a.inferred ? 1 : 0) - (b.inferred ? 1 : 0) || G.compareText(a.childId, b.childId) || G.compareText(a.parentId, b.parentId);
    });
    var used = new Map();
    if (level === 'keys') {
      relations.forEach(function (r) {
        if (!used.has(r.childId)) used.set(r.childId, new Set());
        if (!used.has(r.parentId)) used.set(r.parentId, new Set());
        r.columns.forEach(function (c) { used.get(r.childId).add(c); });
        r.refColumns.forEach(function (c) { used.get(r.parentId).add(c); });
      });
    }
    var entities = [];
    candidates.forEach(function (n) {
      var all = full.get(n.id);
      var shown = level === 'none' ? [] : level === 'keys'
        ? all.filter(function (c) { return c.pk || c.fk || (used.has(n.id) && used.get(n.id).has(c.name)); }) : all;
      entities.push({ id: n.id, name: n.name, node: n, view: n.kind === 'DB_VIEW', declared: n.attrs.declared !== false,
        comment: n.attrs.comment == null ? null : String(n.attrs.comment), columns: shown });
    });
    entities.sort(function (a, b) { return G.compareText(a.name, b.name) || G.compareText(a.id, b.id); });
    return { entities: entities, relations: relations, systemCatalogs: systemCatalogs };
  }

  var SIMPLE_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;

  function exportEntityName(e) {
    var display = e.declared ? e.name + (e.view ? ' (VIEW)' : '') : e.name + ' (DDL 없음)';
    if (SIMPLE_NAME.test(display)) return display;
    var cleaned = oneLine(display).replace(/"/g, "'").replace(/%/g, '_').replace(/\\/g, '_');
    return '"' + (cleaned ? cleaned : '_') + '"';
  }

  function exportTypeWord(dataType) {
    if (isBlank(dataType)) return 'unknown';
    var s = String(dataType).trim().replace(/[,\s]+/g, '_').replace(/[^A-Za-z0-9_()]/g, '_');
    s = s.split('(_').join('(').split('_)').join(')');
    return /^[A-Za-z_]/.test(s) ? s : '_' + s;
  }

  function quotedText(text) {
    return oneLine(text).replace(/"/g, "'");
  }

  /** 엔티티 하나의 Mermaid 글자. bare면 컬럼과 주석 없이 이름만 적는다(영역 밖의 이웃 테이블). */
  function exportEntityText(e, showComments, bare) {
    var name = exportEntityName(e);
    var out = '';
    if (!bare && showComments && !isBlank(e.comment)) out += '    %% ' + oneLine(e.name) + ': ' + oneLine(e.comment) + '\n';
    if (bare || !e.columns.length) return out + '    ' + name + '\n';
    out += '    ' + name + ' {\n';
    var used = new Set();
    e.columns.forEach(function (c, i) {
      var attr = String(c.name).replace(/[^A-Za-z0-9_]/g, '_');
      if (!/[A-Za-z0-9]/.test(attr)) attr = 'col' + (c.ordinal > 0 ? c.ordinal : i + 1);
      else if (!/^[A-Za-z_]/.test(attr)) attr = '_' + attr;
      var candidate = attr;
      for (var n = 2; used.has(candidate); n++) candidate = attr + '_' + n;
      used.add(candidate);
      out += '        ' + exportTypeWord(c.dataType) + ' ' + candidate;
      var keys = [];
      if (c.pk) keys.push('PK');
      if (c.fk) keys.push('FK');
      if (c.unique && !c.pk) keys.push('UK');
      if (keys.length) out += ' ' + keys.join(', ');
      var parts = [];
      if (candidate !== c.name) parts.push(c.name);
      if ((c.pk || c.nullable === false) && !c.pk) parts.push('NOT NULL');
      if (c.referencedOnly) parts.push(REFERENCED_COLUMN);
      if (showComments && !isBlank(c.comment)) parts.push(c.comment);
      var comment = quotedText(parts.join(', '));
      if (comment) out += ' "' + comment + '"';
      out += '\n';
    });
    return out + '    }\n';
  }

  var FROM_UPGRADE = 'upgrade 스크립트';
  // upgrade 스크립트에는 있는데 현재 정의(전체 DDL)에는 없는 외래 키에 덧붙이는 표시(ErdLabels.NOT_IN_CURRENT).
  var NOT_IN_CURRENT = '현재 정의에 없음';

  function exportRelationLabel(r) {
    var columns = r.columns.join(', ');
    if (!r.inferred) {
      var name = r.constraint != null ? r.constraint : columns;
      if (!r.fromUpgrade) return name;
      return name ? name + ' (' + FROM_UPGRADE + ')' : '(' + FROM_UPGRADE + ')';
    }
    // 현재 정의에 없는 upgrade 외래 키: 근거 수 대신 `upgrade 스크립트, 현재 정의에 없음`, 이름은 제약 이름(없으면 컬럼).
    var note = (r.fromUpgrade ? FROM_UPGRADE + ', ' + NOT_IN_CURRENT : r.evidence > 0 ? '추론 ' + r.evidence + '건' : '추론')
      + (!r.directionKnown ? ', 방향 미상' + (r.directionNote ? ': ' + r.directionNote : '')
        : r.cardinalityUnknown ? ', ' + cardinalityUnknownNote(r.directionFrom) : '');
    var label = r.fromUpgrade && r.constraint != null ? r.constraint : columns;
    return label ? label + ' (' + note + ')' : '(' + note + ')';
  }

  function exportRelationText(r, parentName, childName) {
    var parentMarker = r.parentSide === 'zeroOrOne' ? '|o' : r.parentSide === 'one' ? '||' : '}o';
    var childMarker = r.childSide === 'zeroOrOne' ? 'o|' : r.childSide === 'one' ? '||' : 'o{';
    return '    ' + parentName + ' ' + parentMarker + (r.inferred ? '..' : '--') + childMarker + ' ' + childName + ' : "'
      + quotedText(exportRelationLabel(r)) + '"\n';
  }

  // 방향만 알고 카디널리티를 모르는 추론 관계가 있을 때 머리에 더 적는 주석 줄(MermaidErdRenderer.CARDINALITY_UNKNOWN_LEGEND).
  var CARDINALITY_UNKNOWN_LEGEND = "    %% '" + CARDINALITY_UNKNOWN
    + "'은 방향(왼쪽이 부모)만 DDL 외래 키로 확인하고 카디널리티는 정하지 못한 추론 관계다. 양쪽 끝 표시는 N:M이라는 뜻이 아니다.\n";

  // 추론 관계의 부모 쪽을 0..1로 그리는 까닭(ErdLabels.INFERRED_PARENT_OPTIONAL).
  var INFERRED_PARENT_OPTIONAL = "추론 관계는 제약이 없어 부모가 반드시 있는지 알 수 없으므로 부모 쪽을 '0 또는 1'(|o)로 그린다.";

  /**
   * anyCardinalityUnknown: 다이어그램에 방향만 아는 추론 관계가 하나라도 있는가(있을 때만 범례 한 줄이 붙는다).
   * systemCatalogs: 뺀 시스템 카탈로그 수(전체 다이어그램에만 준다. 0이 아니면 한 줄이 붙는다).
   */
  function exportHeader(entityCount, relationCount, inferredCount, anyCardinalityUnknown, systemCatalogs) {
    return 'erDiagram\n    %% CodeAtlas ERD: 엔티티 ' + entityCount + '개, 관계 ' + relationCount + '개 (추론 ' + inferredCount + '개)\n'
      + '    %% 실선(--)은 DDL 외래 키, 점선(..)은 SQL에서 추론한 관계다. ' + INFERRED_PARENT_OPTIONAL + '\n'
      + (anyCardinalityUnknown ? CARDINALITY_UNKNOWN_LEGEND : '')
      + (systemCatalogs > 0 ? '    %% 시스템 카탈로그 ' + systemCatalogs + '개(DB가 제공하는 데이터 사전)는 프로젝트 스키마가 아니라서 뺐다.\n' : '');
  }

  /**
   * 표준 산출물의 erDiagram 글자(erd/erd.mmd와 같다). entities·relations는 exportModel의 것(또는 그 일부)이다.
   * opts.comments: 테이블·컬럼 주석을 적는다(기본 true). opts.bare: 컬럼 없이 이름만 적을 엔티티 ID 집합.
   * opts.systemCatalogs: 뺀 시스템 카탈로그 수(전체 다이어그램일 때 exportModel의 systemCatalogs.length를 준다).
   */
  function exportMermaid(index, entities, relations, opts) {
    var o = opts || {};
    var showComments = o.comments !== false;
    var names = new Map();
    var out = exportHeader(entities.length, relations.length, relations.filter(function (r) { return r.inferred; }).length,
      relations.some(function (r) { return r.cardinalityUnknown; }), o.systemCatalogs || 0);
    entities.forEach(function (e) {
      names.set(e.id, exportEntityName(e));
      out += exportEntityText(e, showComments, !!(o.bare && o.bare.has(e.id)));
    });
    relations.forEach(function (r) { out += exportRelationText(r, names.get(r.parentId), names.get(r.childId)); });
    return out;
  }

  /** 이름을 `_`로 나눈 앞 tokens개 토큰(대문자). 토큰이 둘 이상이면 마지막 하나는 남긴다(이름 전체가 접두사가 되지 않게). */
  function areaPrefix(tableName, tokens) {
    var upper = String(tableName).toUpperCase();
    var parts = upper.split('_').filter(Boolean);
    if (!parts.length) return upper;
    var take = Math.min(tokens, Math.max(1, parts.length - 1));
    return parts.slice(0, take).join('_');
  }

  function fileSafe(text) {
    var out = '';
    var chars = Array.from(String(text));
    for (var i = 0; i < chars.length; i++) out += /^[\p{L}\p{N}._-]$/u.test(chars[i]) ? chars[i] : '_';
    return out || '_';
  }

  function areaOrder(name) {
    return (String(name).indexOf(ETC) === 0 ? '￿' : '') + name;
  }

  /**
   * 테이블을 주제 영역으로 나눈다. 표준 산출물이 큰 ERD를 나눌 때의 규칙(ErdSplitter)과 같다: 이름을 `_`로 나눈 첫 토큰이 같은 테이블끼리
   * 묶고, 한 묶음의 다이어그램(표준 산출물의 Mermaid 글자)이 한도를 넘으면 둘째 토큰까지로 다시 나누고, 테이블이 3개 미만인 묶음은
   * 관계로 묶는다(관계로 이어진 테이블끼리 한 영역, 한도를 넘으면 가장 많이 참조되는 테이블들을 중심으로 나눔. 영역 이름은 그 안에서 가장 많이
   * 참조되는 테이블. 전체의 20% 넘는 테이블이 참조하는 허브는 영역에 넣지 않고 hubs로 알린다). 그러고도 남는 테이블은
   * `기타`로 합치고, 그래도 한도를 넘는 묶음은 이름순으로 잘라 `<영역>-1`, `<영역>-2`…로 만든다.
   *
   * 5차 수정(F-013-R21~R24, 표준 산출물과 같다):
   * - 관계로 묶은 묶음이 테이블 30개(MAX_AREA_TABLES)나 글자 수 한도를 넘으면 그 안에서 가장 많이 참조되는 테이블(중심)을 빼고 나머지를
   *   다시 묶는다. 중심은 자기 이름의 영역에 있고(중심을 빼면 3개 미만으로 남는 조각과 함께) 그것을 참조하는 다른 영역에는 이름만 나온다.
   *   중심을 빼도 갈리지 않으면(가장 큰 조각이 묶음의 3/4을 넘으면) 전처럼 중심 여럿에 가까운 테이블끼리 나눈다.
   * - 관계로 묶이지 않고 남은 테이블은 이름의 공통 접두사로 묶는다(가장 긴 접두사부터, 3개 이상·접두사 3자 이상. 이름은 `COMTNSMS*`).
   *   묶이지 않는 나머지만 `기타`다.
   * - 허브와의 관계는 outerRelations(다른 영역과의 관계)에 넣지 않고 hubRelations로 따로 센다.
   * - 영역 이름이 된 테이블에 논리명(comment)이 있으면 label은 `논리명(물리명)`이다. name과 fileName은 물리명 그대로다.
   *
   * opts: exportModel의 옵션 + comments(기본 true) + maxChars(영역 다이어그램 하나의 글자 수 한도, 기본 45,000).
   * 결과: { areas: [{ name, label(표시 이름), fileName(erd-<fileName>.mmd), tables: [노드], ids: Set, innerRelations,
   *                  outerRelations(다른 영역과의 관계), hubRelations(허브와의 관계), chars }],
   *        crossRelations: [{ from, to, count }], total(테이블 수), relations(관계 수), maxChars, fullChars(전체 다이어그램의 글자 수),
   *        oversize(전체가 한도를 넘는지: 표준 산출물이 영역으로 나누는 조건),
   *        hubs: [{ id, name, referencedBy }](어느 영역에도 넣지 않은 허브 테이블), byRelations(관계로 묶은 영역이 있는지),
   *        centers: [{ id, name, referencedBy, areas: [중심을 빼고 나눈 영역의 name] }], byNames(이름 접두로 묶은 영역이 있는지) }
   * 같은 옵션의 결과는 색인에 붙여 둔다.
   */
  function splitAreas(index, opts) {
    var o = opts || {};
    var maxChars = o.maxChars > 0 ? o.maxChars : DEFAULT_MAX_CHARS;
    var showComments = o.comments !== false;
    var cacheKey = [o.includeViews === true, o.includeInferred !== false, o.minEvidence == null ? 1 : o.minEvidence, o.columns || 'all', showComments, maxChars].join('|');
    var cache = index._erdAreas || (index._erdAreas = new Map());
    if (cache.has(cacheKey)) return cache.get(cacheKey);

    var model = exportModel(index, o);
    // 글자 수는 조각의 합으로 센다: 엔티티(컬럼 포함), 이름만 적은 엔티티, 관계 줄. 묶음을 바꿔 가며 여러 번 재므로 다이어그램을 매번 만들지 않는다.
    var fullLength = new Map();
    var bareLength = new Map();
    var names = new Map();
    model.entities.forEach(function (e) {
      names.set(e.id, exportEntityName(e));
      fullLength.set(e.id, exportEntityText(e, showComments, false).length);
      bareLength.set(e.id, exportEntityText(e, showComments, true).length);
    });
    var relationsOf = new Map();
    model.relations.forEach(function (r) {
      r._length = exportRelationText(r, names.get(r.parentId), names.get(r.childId)).length;
      [r.childId, r.parentId].forEach(function (id, i) {
        if (i === 1 && r.childId === r.parentId) return;
        if (!relationsOf.has(id)) relationsOf.set(id, []);
        relationsOf.get(id).push(r);
      });
    });
    // 허브 테이블(어느 영역에도 넣지 않는다). 관계로 묶을 때 채운다.
    var hubIds = new Set();
    /**
     * 영역(inside) 다이어그램의 글자 수와 관계 수: 영역의 엔티티 + 관계로 이어진 밖의 엔티티(이름만) + 한쪽 끝이라도 영역 안인 관계.
     * outer는 한쪽 끝이 영역 밖인 관계 전부이고, hub는 그 가운데 다른 끝이 허브인 것이다.
     */
    var measure = function (inside) {
      var ids = new Set();
      inside.forEach(function (e) { ids.add(e.id); });
      var neighbors = new Set();
      var counted = new Set();
      var length = 0;
      var inner = 0;
      var outer = 0;
      var hub = 0;
      var inferred = 0;
      var anyCardinalityUnknown = false;
      inside.forEach(function (e) {
        length += fullLength.get(e.id);
        (relationsOf.get(e.id) || []).forEach(function (r) {
          if (counted.has(r)) return;
          counted.add(r);
          length += r._length;
          if (r.inferred) inferred++;
          if (r.cardinalityUnknown) anyCardinalityUnknown = true;
          var child = ids.has(r.childId);
          var parent = ids.has(r.parentId);
          if (child && parent) inner++;
          else { outer++; if (hubIds.has(child ? r.parentId : r.childId)) hub++; }
          if (!child) neighbors.add(r.childId);
          if (!parent) neighbors.add(r.parentId);
        });
      });
      neighbors.forEach(function (id) { length += bareLength.get(id); });
      length += exportHeader(inside.length + neighbors.size, counted.size, inferred, anyCardinalityUnknown).length;
      return { chars: length, inner: inner, outer: outer, hub: hub, neighbors: neighbors };
    };
    var fits = function (entities) { return measure(entities).chars <= maxChars; };
    /** 관계로 묶은 영역과 이름 접두 군집의 상한: 테이블 30개, 글자 수 한도. */
    var tooBig = function (entities) { return entities.length > MAX_AREA_TABLES || !fits(entities); };
    var sortedKeys = function (map) { return Array.from(map.keys()).sort(G.compareText); };
    var push = function (map, key, e) {
      if (!map.has(key)) map.set(key, []);
      map.get(key).push(e);
    };

    // 1) 첫 토큰으로 묶는다
    var groups = new Map();
    model.entities.forEach(function (e) { push(groups, areaPrefix(e.name, 1), e); });
    // 2) 한도를 넘는 묶음은 둘째 토큰까지로 다시 묶는다
    var refined = new Map();
    sortedKeys(groups).forEach(function (key) {
      var list = groups.get(key);
      if (list.length < 2 || fits(list)) { list.forEach(function (e) { push(refined, key, e); }); return; }
      list.forEach(function (e) { push(refined, areaPrefix(e.name, 2), e); });
    });
    // 3) 작은 묶음(접두사로 묶이지 않는 테이블)은 관계로 묶는다(ErdSplitter.clusterByRelations와 같다)
    var named = new Map();
    var leftover = [];
    sortedKeys(refined).forEach(function (key) {
      var list = refined.get(key);
      if (list.length < MIN_AREA_SIZE) leftover = leftover.concat(list);
      else named.set(key, list);
    });
    var prefixAreas = named.size > 0;
    var byName = function (a, b) { return G.compareText(a.name, b.name) || G.compareText(a.id, b.id); };
    var clusters = (function () {
      var candidates = leftover.slice().sort(byName);
      var byId = new Map();
      candidates.forEach(function (e) { byId.set(e.id, e); });
      // 테이블 → 그것을 참조하는 테이블(자기 참조 제외). 허브 판정은 전체 테이블 기준이다.
      var referencedBy = new Map();
      model.relations.forEach(function (r) {
        if (r.childId === r.parentId) return;
        if (!referencedBy.has(r.parentId)) referencedBy.set(r.parentId, new Set());
        referencedBy.get(r.parentId).add(r.childId);
      });
      var hubs = [];
      candidates.forEach(function (e) {
        var count = referencedBy.has(e.id) ? referencedBy.get(e.id).size : 0;
        if (count * 100 > model.entities.length * HUB_PERCENT) { hubs.push({ id: e.id, name: e.name, referencedBy: count }); hubIds.add(e.id); }
      });
      var adjacent = new Map();
      var link = function (a, b) { if (!adjacent.has(a)) adjacent.set(a, new Set()); adjacent.get(a).add(b); };
      model.relations.forEach(function (r) {
        var a = r.childId, b = r.parentId;
        if (a === b || !byId.has(a) || !byId.has(b) || hubIds.has(a) || hubIds.has(b)) return;
        link(a, b);
        link(b, a);
      });
      var neighborsOf = function (id) { return adjacent.has(id) ? Array.from(adjacent.get(id)).sort(G.compareText) : []; };
      /** 테이블들을 그 묶음 안에서 참조되는 수가 많은 순(같으면 이름, ID 순)으로 늘어놓는다. */
      var mostReferenced = function (entities) {
        var ids = new Set(entities.map(function (e) { return e.id; }));
        var counts = new Map();
        entities.forEach(function (e) {
          var n = 0;
          if (referencedBy.has(e.id)) referencedBy.get(e.id).forEach(function (child) { if (ids.has(child)) n++; });
          counts.set(e.id, n);
        });
        return entities.slice().sort(function (a, b) { return counts.get(b.id) - counts.get(a.id) || byName(a, b); });
      };
      /**
       * 한도(테이블 30개, 글자 수)를 넘는 연결 요소를 중심 k개로 나눈다: 테이블은 관계를 따라 가장 가까운 중심(거리가 같으면 먼저 고른 중심)에 붙는다.
       * 첫 중심은 가장 많이 참조되는 테이블이고, 다음 중심은 이미 고른 중심들에서 가장 먼 테이블이다(거리가 같으면 많이 참조되는 순, 이름순).
       * 서로 먼 테이블을 중심으로 삼아야 조각이 고르게 나온다(사슬 모양에서 이웃한 테이블들이 중심이 되면 낱개로 흩어진다).
       * 조각이 모두 한도 안에 드는 가장 작은 k의 결과를 돌려준다.
       */
      var splitAroundCenters = function (component) {
        var ranked = mostReferenced(component);
        var inside = new Set(component.map(function (e) { return e.id; }));
        var seeds = [ranked[0].id];
        while (seeds.length < ranked.length) {
          // 다음 중심: 지금의 중심들에서 가장 먼 테이블
          var distance = new Map();
          var reach = seeds.slice();
          seeds.forEach(function (id) { distance.set(id, 0); });
          for (var at = 0; at < reach.length; at++) {
            var from = reach[at];
            neighborsOf(from).forEach(function (next) {
              if (inside.has(next) && !distance.has(next)) { distance.set(next, distance.get(from) + 1); reach.push(next); }
            });
          }
          var farthest = null;
          var far = 0;
          ranked.forEach(function (e) { var d = distance.get(e.id); if (d > far) { far = d; farthest = e.id; } });
          if (farthest == null) break;
          seeds.push(farthest);
          var owner = new Map();
          var queue = seeds.slice();
          seeds.forEach(function (id, i) { owner.set(id, i); });
          for (var head = 0; head < queue.length; head++) {
            var current = queue[head];
            neighborsOf(current).forEach(function (next) {
              if (inside.has(next) && !owner.has(next)) { owner.set(next, owner.get(current)); queue.push(next); }
            });
          }
          var parts = [];
          for (var p = 0; p < seeds.length; p++) parts.push([]);
          component.forEach(function (e) { parts[owner.get(e.id)].push(e); });
          if (parts.every(function (part) { return part.length === 1 || !tooBig(part); })) return parts;
        }
        return component.map(function (e) { return [e]; });
      };
      /** entities(이름순) 안에서 관계로 이어진 묶음들. skipId의 테이블은 빼고 잇는다. 묶음은 이름이 앞선 테이블이 든 것부터, 묶음 안은 이름순. */
      var componentsOf = function (entities, skipId) {
        var inside = new Set(entities.map(function (e) { return e.id; }));
        var seen = new Set();
        if (skipId != null) seen.add(skipId);
        var out = [];
        entities.forEach(function (start) {
          if (seen.has(start.id)) return;
          seen.add(start.id);
          var component = [];
          var queue = [start.id];
          for (var head = 0; head < queue.length; head++) {
            component.push(byId.get(queue[head]));
            neighborsOf(queue[head]).forEach(function (next) { if (inside.has(next) && !seen.has(next)) { seen.add(next); queue.push(next); } });
          }
          component.sort(byName);
          out.push(component);
        });
        return out;
      };
      var areas = new Map();
      // 영역 이름 → 그 이름이 된 테이블의 논리명(comment)
      var comments = new Map();
      var centers = [];
      var rest = [];
      /** namer의 이름으로 영역을 만들고 그 이름을 돌려준다(이름이 같은 테이블이 이미 영역 이름이면 -2…를 붙인다). */
      var addArea = function (namer, entities) {
        var name = namer.name;
        for (var n = 2; areas.has(name); n++) name = namer.name + '-' + n;
        areas.set(name, entities);
        comments.set(name, namer.comment);
        return name;
      };
      /**
       * 묶음 하나를 영역으로 만들고 만든 영역의 이름을 돌려준다. 3개 미만이면 나머지로 돌린다. 한도를 넘으면 가장 많이 참조되는 테이블(중심)을 빼고
       * 다시 묶는다: 3개 이상인 조각은 저마다 영역이 되고(아직 크면 같은 방법으로 또 나눈다), 3개 미만인 조각은 중심과 함께 중심의 영역이 된다.
       * 중심을 빼도 갈리지 않으면(가장 큰 조각이 묶음의 3/4을 넘으면) 중심 여럿에 가까운 테이블끼리 나눈다.
       */
      var place = function (component) {
        if (component.length < MIN_AREA_SIZE) { rest = rest.concat(component); return []; }
        if (!tooBig(component)) return [addArea(mostReferenced(component)[0], component)];
        var center = mostReferenced(component)[0];
        var subs = componentsOf(component, center.id);
        var largest = 0;
        subs.forEach(function (sub) { if (sub.length > largest) largest = sub.length; });
        var made = [];
        if (largest * 4 > component.length * 3) {
          splitAroundCenters(component).forEach(function (part) {
            if (part.length < MIN_AREA_SIZE) rest = rest.concat(part);
            else made.push(addArea(mostReferenced(part)[0], part));
          });
          return made;
        }
        var own = [center];
        subs.forEach(function (sub) {
          if (sub.length < MIN_AREA_SIZE) own = own.concat(sub);
          else made = made.concat(place(sub));
        });
        own.sort(byName);
        var name = addArea(center, own);
        centers.push({ id: center.id, name: center.name, referencedBy: referencedBy.has(center.id) ? referencedBy.get(center.id).size : 0,
          areas: made.sort(G.compareText) });
        return [name];
      };
      componentsOf(candidates.filter(function (e) { return !hubIds.has(e.id); }), null).forEach(place);
      centers.sort(function (a, b) { return byName(a, b); });
      return { areas: areas, comments: comments, hubs: hubs, centers: centers, rest: rest };
    })();
    // 영역 이름 → 테이블 수 상한(없으면 글자 수 한도만 본다), 영역 이름 → 표시 이름에 넣을 논리명
    var caps = new Map();
    var areaComments = new Map();
    var renamed = new Map();
    sortedKeys(clusters.areas).forEach(function (key) {
      // 관계로 묶은 영역의 이름(테이블 이름)이 접두사 영역의 이름과 겹치면 접두사 영역이 그 이름을 갖는다.
      var unique = key;
      for (var n = 2; named.has(unique); n++) unique = key + '-' + n;
      named.set(unique, clusters.areas.get(key));
      caps.set(unique, MAX_AREA_TABLES);
      areaComments.set(unique, clusters.comments.get(key));
      renamed.set(key, unique);
    });
    clusters.centers.forEach(function (center) { center.areas = center.areas.map(function (name) { return renamed.get(name); }); });
    // 3-2) 관계로 묶이지 않고 남은 테이블은 이름의 공통 접두사로 묶는다(ErdSplitter.clusterByNames와 같다): 대문자로 바꾼 이름을 한 글자씩
    // 따라 내려가며, 가장 긴 접두사부터 그 접두사를 가진 테이블 가운데 아직 묶이지 않은 것이 3개 이상이면 한 영역(`<접두사>*`)으로 묶는다.
    var nameClusters = new Map();
    var etc = (function () {
      var items = clusters.rest.map(function (e) { return { e: e, key: String(e.name).toUpperCase() }; })
        .sort(function (a, b) { return G.compareText(a.key, b.key) || byName(a.e, b.e); });
      var commonLength = function (list, from) {
        var first = list[0].key;
        for (var n = from; n < first.length; n++) {
          var c = first.charCodeAt(n);
          for (var i = 1; i < list.length; i++) if (list[i].key.length <= n || list[i].key.charCodeAt(n) !== c) return n;
        }
        return first.length;
      };
      /** 앞 from 글자가 같은 테이블들(이름순)을 묶고, 묶이지 않은 것을 돌려준다. */
      var visit = function (list, from) {
        if (list.length < MIN_AREA_SIZE) return list;
        var common = commonLength(list, from);
        var remaining = [];
        var start = 0;
        while (start < list.length) {
          if (list[start].key.length === common) { remaining.push(list[start]); start++; continue; }
          var c = list[start].key.charCodeAt(common);
          var end = start + 1;
          while (end < list.length && list[end].key.length > common && list[end].key.charCodeAt(common) === c) end++;
          remaining = remaining.concat(visit(list.slice(start, end), common + 1));
          start = end;
        }
        if (remaining.length >= MIN_AREA_SIZE && common >= MIN_NAME_PREFIX) {
          nameClusters.set(list[0].key.substring(0, common) + '*', remaining.map(function (item) { return item.e; }).sort(byName));
          return [];
        }
        return remaining;
      };
      return items.length ? visit(items, 0).map(function (item) { return item.e; }) : [];
    })();
    sortedKeys(nameClusters).forEach(function (key) {
      var unique = key;
      for (var n = 2; named.has(unique); n++) unique = key + '-' + n;
      named.set(unique, nameClusters.get(key));
      caps.set(unique, MAX_AREA_TABLES);
    });
    var merged = new Map();
    sortedKeys(named).forEach(function (key) { merged.set(key, named.get(key)); });
    if (!prefixAreas && !clusters.areas.size && !clusters.hubs.length && !nameClusters.size && etc.length) {
      // 접두사로도 관계로도 이름으로도 묶이는 테이블이 없다: 전체를 한 묶음으로 본다.
      merged.set('ALL', etc);
      etc = [];
    }
    if (etc.length) {
      etc.sort(byName);
      merged.set(ETC, etc);
    }
    // 4) 아직 한도를 넘는 묶음은 한도 안에 드는 가장 적은 조각 수로 자른다(관계로 묶은 영역과 이름 접두 군집은 테이블 30개도 한도다)
    var finalGroups = [];
    merged.forEach(function (list, key) {
      var cap = caps.has(key) ? caps.get(key) : Infinity;
      var chunks = [list];
      for (var parts = 1; parts <= list.length; parts++) {
        var size = Math.floor((list.length + parts - 1) / parts);
        var candidate = [];
        var ok = true;
        for (var from = 0; from < list.length && ok; from += size) {
          var chunk = list.slice(from, Math.min(list.length, from + size));
          ok = chunk.length === 1 || (chunk.length <= cap && fits(chunk));
          candidate.push(chunk);
        }
        if (ok) { chunks = candidate; break; }
      }
      var label = function (name) {
        var comment = oneLine(areaComments.get(key));
        if (!comment) return name;
        var points = Array.from(comment);
        return (points.length > MAX_LABEL_COMMENT ? points.slice(0, MAX_LABEL_COMMENT).join('') + '…' : comment) + '(' + name + ')';
      };
      if (chunks.length === 1) finalGroups.push({ name: key, label: label(key), entities: chunks[0] });
      else chunks.forEach(function (chunk, i) { finalGroups.push({ name: key + '-' + (i + 1), label: label(key + '-' + (i + 1)), entities: chunk }); });
    });

    var areaOf = new Map();
    finalGroups.forEach(function (g) { g.entities.forEach(function (e) { areaOf.set(e.id, g.name); }); });
    var cross = new Map();
    model.relations.forEach(function (r) {
      var a = areaOf.get(r.childId);
      var b = areaOf.get(r.parentId);
      if (a == null || b == null || a === b) return;
      var key = areaOrder(a) <= areaOrder(b) ? a + '\t' + b : b + '\t' + a;
      cross.set(key, (cross.get(key) || 0) + 1);
    });
    var crossRelations = Array.from(cross.keys()).map(function (key) {
      var pair = key.split('\t');
      return { from: pair[0], to: pair[1], count: cross.get(key) };
    }).sort(function (x, y) { return G.compareText(areaOrder(x.from), areaOrder(y.from)) || G.compareText(areaOrder(x.to), areaOrder(y.to)); });

    var usedFileNames = new Set();
    var areas = finalGroups.map(function (g) {
      var m = measure(g.entities);
      // 이름 접두 군집(`COMTNSMS*`)의 파일 이름에는 `*`를 넣지 않는다.
      var base = g.name === ETC ? 'etc' : g.name.indexOf(ETC + '-') === 0 ? 'etc' + g.name.substring(ETC.length) : fileSafe(g.name.split('*').join(''));
      var fileName = base;
      for (var n = 2; usedFileNames.has(fileName.toLowerCase()); n++) fileName = base + '_' + n;
      usedFileNames.add(fileName.toLowerCase());
      var ids = new Set();
      g.entities.forEach(function (e) { ids.add(e.id); });
      return { name: g.name, label: g.label, fileName: fileName, tables: g.entities.map(function (e) { return e.node; }), ids: ids, neighbors: m.neighbors,
        innerRelations: m.inner, outerRelations: m.outer - m.hub, hubRelations: m.hub, chars: m.chars };
    });
    var fullChars = exportHeader(model.entities.length, model.relations.length, model.relations.filter(function (r) { return r.inferred; }).length,
      model.relations.some(function (r) { return r.cardinalityUnknown; }), model.systemCatalogs.length).length;
    model.entities.forEach(function (e) { fullChars += fullLength.get(e.id); });
    model.relations.forEach(function (r) { fullChars += r._length; });
    var result = { areas: areas, crossRelations: crossRelations, total: model.entities.length, relations: model.relations.length,
      maxChars: maxChars, fullChars: fullChars, oversize: fullChars > maxChars, systemCatalogs: model.systemCatalogs.length,
      hubs: clusters.hubs, byRelations: clusters.areas.size > 0, centers: clusters.centers, byNames: nameClusters.size > 0 };
    cache.set(cacheKey, result);
    return result;
  }

  /** 영역 하나를 그릴 범위. outside는 관계로 이어진 다른 영역의 테이블(컬럼 없이 이름만 그린다). 없는 영역이면 빈 범위다. */
  function areaScope(index, o) {
    var split = splitAreas(index, { includeViews: o.includeViews, includeInferred: o.includeInferred, comments: o.comments, columns: o.columns, maxChars: o.maxChars });
    var area = null;
    split.areas.forEach(function (a) { if (a.name === o.area) area = a; });
    if (!area) return { tables: [], relations: [], total: 0, truncated: false, outside: new Set(), area: null };
    var includeInferred = o.includeInferred !== false;
    var inScope = new Set(area.ids);
    area.neighbors.forEach(function (id) { inScope.add(id); });
    var tables = [];
    inScope.forEach(function (id) { var n = index.node(id); if (n) tables.push(n); });
    tables.sort(function (a, b) { return G.compareText(a.name, b.name) || G.compareText(a.id, b.id); });
    var relations = [];
    var declaredKeys = new Set();
    tables.forEach(function (t) {
      index.out(t.id).forEach(function (e) {
        if (e.kind !== 'REFERENCES' || !inScope.has(e.to)) return;
        // 양 끝이 모두 영역 밖이면 이 영역의 관계가 아니다.
        if (!area.ids.has(e.from) && !area.ids.has(e.to)) return;
        var r = relationOf(e);
        if (!includeInferred && r.source === 'INFERRED') return;
        relations.push(r);
        if (r.source !== 'INFERRED') declaredKeys.add(r.from + '\u0000' + r.to + '\u0000' + r.columns.join(','));
      });
    });
    relations = relations.filter(function (r) {
      return r.source !== 'INFERRED' || !declaredKeys.has(r.from + '\u0000' + r.to + '\u0000' + r.columns.join(','));
    });
    return { tables: tables, relations: relations, total: tables.length, truncated: false, outside: new Set(area.neighbors), area: area };
  }

  // ---------- Mermaid

  var SAFE_ENTITY = /^[A-Za-z_][A-Za-z0-9_]*$/;
  // erDiagram 문법이 예약한 낱말(대소문자 구분 없음).
  var RESERVED = { STYLE: 1, CLASSDEF: 1, CLASS: 1, SUBGRAPH: 1, END: 1, ONE: 1, MANY: 1, TO: 1, U: 1, ERDIAGRAM: 1, DIRECTION: 1, TITLE: 1, ACCTITLE: 1, ACCDESCR: 1 };

  function entityIds(tables) {
    var used = new Set();
    var ids = new Map();
    var isSafe = function (t) { return SAFE_ENTITY.test(t.name) && !RESERVED[t.name.toUpperCase()]; };
    // 그대로 쓸 수 있는 이름을 먼저 차지하게 하고, 나머지는 겹치지 않는 별칭을 만든다(테이블과 뷰의 이름이 같아도 겹치지 않는다).
    tables.forEach(function (t) {
      if (isSafe(t) && !used.has(t.name)) { used.add(t.name); ids.set(t.id, t.name); }
    });
    tables.forEach(function (t, i) {
      if (ids.has(t.id)) return;
      var base = String(t.name).replace(/[^A-Za-z0-9_]/g, '_');
      if (!/[A-Za-z0-9]/.test(base)) base = 'T' + i;
      if (!/^[A-Za-z_]/.test(base) || RESERVED[base.toUpperCase()]) base = 'T_' + base;
      var candidate = base;
      for (var k = 2; used.has(candidate); k++) candidate = base + '_' + k;
      used.add(candidate);
      ids.set(t.id, candidate);
    });
    return ids;
  }

  function cleanQuoted(text) {
    return String(text).replace(/"/g, "'").replace(/[%\\\r\n\t]/g, ' ');
  }

  // erDiagram의 속성 낱말: 글자나 밑줄로 시작하고 글자, 숫자, - _ [ ] ( ) . , 를 쓸 수 있다.
  function attributeWord(text, fallback) {
    var w = String(text == null ? '' : text).trim().replace(/\s+/g, '_').replace(/[^A-Za-z0-9\-_\[\]\(\)\.,À-￿]/g, '_');
    if (!w) return fallback;
    if (!/^[A-Za-z_À-￿]/.test(w)) w = '_' + w;
    if (/^(PK|FK|UK)$/i.test(w)) w += '_';
    return w;
  }

  function nameSet(list) {
    return new Set(Array.isArray(list) ? list : []);
  }

  /** 테이블 속성 uniqueKeys("COL1,COL2")를 컬럼 이름 배열들로 나눈다. */
  function uniqueKeysOf(table) {
    var keys = [];
    (Array.isArray(table.attrs.uniqueKeys) ? table.attrs.uniqueKeys : []).forEach(function (key) {
      var cols = String(key).split(',').map(function (c) { return c.trim(); }).filter(Boolean);
      if (cols.length) keys.push(cols);
    });
    return keys;
  }

  /**
   * 테이블(뷰)의 컬럼 표시 정보. PK와 UK는 컬럼의 primaryKey·unique와 테이블의 primaryKey·uniqueKeys(단일 컬럼) 어느 쪽에 있어도 표시한다.
   * FK는 DDL에 선언된 외래 키의 자식 쪽 컬럼이다(부모 테이블이 범위 밖이어도 FK다). 추론 관계의 컬럼은 FK로 표시하지 않는다.
   */
  function columnInfo(index, table) {
    var tablePk = nameSet(table.attrs.primaryKey);
    var singleUnique = new Set();
    uniqueKeysOf(table).forEach(function (cols) { if (cols.length === 1) singleUnique.add(cols[0]); });
    var fks = new Set();
    index.out(table.id).forEach(function (e) {
      if (e.kind !== 'REFERENCES') return;
      var r = relationOf(e);
      if (r.source !== 'INFERRED') r.columns.forEach(function (c) { fks.add(c); });
    });
    // DDL에 없는데 SQL이 참조하는 컬럼(정의가 있는 테이블의 declared=false 컬럼)은 그리지 않는다. 상세에서는 '정의 없음'으로 보인다.
    return index.columnsOf(table.id).filter(function (c) { return table.attrs.declared === false || c.attrs.declared !== false; }).map(function (c) {
      var pk = c.attrs.primaryKey === true || tablePk.has(c.name);
      return {
        node: c, name: c.name, pk: pk, fk: fks.has(c.name),
        unique: c.attrs.unique === true || singleUnique.has(c.name),
        nullable: c.attrs.nullable === true
      };
    });
  }

  /** "N:1" 같은 cardinality를 [자식 쪽, 부모 쪽]으로 읽는다. 값은 'one', 'zeroOrOne', 'many'. 읽지 못하면(unknown 포함) null. */
  function parseCardinality(text) {
    var parts = String(text || '').trim().split(':');
    if (parts.length !== 2) return null;
    var side = function (token) {
      switch (token.trim().toUpperCase()) {
        case '1': case '1..1': return 'one';
        case '0..1': return 'zeroOrOne';
        case 'N': case 'M': case '*': case '0..*': case '1..*': case '0..N': case '1..N': return 'many';
        default: return null;
      }
    };
    var child = side(parts[0]);
    var parent = side(parts[1]);
    return child && parent ? [child, parent] : null;
  }

  /**
   * 관계 선의 양 끝. 표준 산출물(erd.mmd)과 같은 규칙이다.
   * 방향을 모르면 양쪽 }o o{ (그린 뒤에 끝 표시를 지운다). 엣지의 cardinality가 있으면 그것을 따르고,
   * 없으면(DDL 관계는 unknown이어도) 자식 컬럼으로 정한다: FK 컬럼이 자식의 PK 전체를 덮거나 unique면 자식 쪽 0..1, nullable이면 부모 쪽 0..1.
   * 추론 관계의 부모 쪽은 항상 0..1이다(제약이 없어 "반드시 하나"를 알 수 없다. 엣지의 cardinality가 N:1이어도 같다).
   * 방향은 알지만 카디널리티를 모르는 추론 관계(cardinality=unknown)는 계산하지 않고 양쪽 다 가장 약한 표시 }o o{ 로 둔다(끝 표시를 지우지 않는다).
   */
  function markers(r, childColumns, childUniqueKeys) {
    if (!r.directionKnown || r.cardinalityUnknown) return { parent: '}o', child: 'o{' };
    var byName = new Map();
    childColumns.forEach(function (c) { byName.set(c.name, c); });
    var fk = new Set(r.columns);
    var pk = childColumns.filter(function (c) { return c.pk; }).map(function (c) { return c.name; });
    var unique = fk.size > 0 && ((pk.length > 0 && pk.every(function (n) { return fk.has(n); }))
      || r.columns.some(function (n) { return byName.has(n) && byName.get(n).unique; })
      || childUniqueKeys.some(function (key) { return key.every(function (n) { return fk.has(n); }); }));
    var optional = r.columns.some(function (n) { return byName.has(n) && !byName.get(n).pk && byName.get(n).nullable; });
    var child = unique ? 'zeroOrOne' : 'many';
    // 추론 관계에는 제약이 없다: 부모가 반드시 있는지 알 수 없으므로 부모 쪽은 0..1이다(F-013-R28).
    var parent = optional || r.source === 'INFERRED' ? 'zeroOrOne' : 'one';
    var explicit = parseCardinality(r.cardinality);
    if (explicit) {
      // 자식 쪽의 "1"은 부모마다 자식이 많아야 하나라는 뜻이라 0..1로 그린다.
      child = explicit[0] === 'many' ? 'many' : 'zeroOrOne';
      if (explicit[1] === 'zeroOrOne') parent = 'zeroOrOne';
    }
    return { parent: parent === 'zeroOrOne' ? '|o' : '||', child: child === 'zeroOrOne' ? 'o|' : 'o{' };
  }

  function sameList(a, b) {
    return a.length === b.length && a.every(function (x, i) { return x === b[i]; });
  }

  var fixedText = G.fixedText;

  /**
   * 관계 라벨. 표준 산출물(erd.mmd)과 같은 표기다: DDL 관계는 제약 이름(없으면 컬럼), 추론 관계는 `컬럼 (추론 n건)`,
   * 방향을 모르면 `컬럼 (추론 n건, 방향 미상)`, 방향만 알고 카디널리티를 모르면 `컬럼 (추론 n건, 방향은 DDL, 카디널리티 모름)`.
   * 뷰어는 여기에 더해, 컬럼 이름이 양쪽에서 다르면 상대 쪽 컬럼을,
   * 복합 키의 일부를 상수로 고정한 관계(공통코드 테이블)면 고정 컬럼을 적는다: `ORDER_STATUS → CODE (GROUP_CD='ORDER_STATUS', 추론 1건)`.
   */
  function relationLabel(r) {
    if (r.source === 'INFERRED' && r.fromUpgrade) {
      // 현재 정의에 없는 upgrade 외래 키: 추론 n건 대신 `(upgrade 스크립트, 현재 정의에 없음)`. 이름은 제약 이름(없으면 컬럼).
      var name = r.constraint ? r.constraint
        : r.columns.join(', ') + (r.columns.length && r.refColumns.length && !sameList(r.columns, r.refColumns) ? (r.directionKnown ? ' → ' : ' = ') + r.refColumns.join(', ') : '');
      var why = FROM_UPGRADE + ', ' + NOT_IN_CURRENT + (!r.directionKnown ? ', 방향 미상' + (r.directionNote ? ': ' + r.directionNote : '')
        : r.cardinalityUnknown ? ', ' + cardinalityUnknownNote(r.directionFrom) : '');
      return (name ? name + ' ' : '') + '(' + why + ')';
    }
    if (r.source === 'INFERRED') {
      var text = '';
      var fixed = fixedText(r.fixedColumns, r.fixedValues);
      if (r.columns.length) {
        text = r.columns.join(', ');
        // 방향을 모르면 화살표 대신 등호로. 고정 컬럼이 있으면 부모 쪽 키가 보이게 늘 적는다.
        if (r.refColumns.length && (fixed || !sameList(r.columns, r.refColumns))) text += (r.directionKnown ? ' → ' : ' = ') + r.refColumns.join(', ');
      }
      var notes = [];
      if (fixed) notes.push(fixed);
      notes.push(r.evidenceCount > 0 ? '추론 ' + r.evidenceCount + '건' : '추론');
      if (!r.directionKnown) notes.push('방향 미상' + (r.directionNote ? ': ' + r.directionNote : ''));
      else if (r.cardinalityUnknown) notes.push(cardinalityUnknownNote(r.directionFrom));
      return (text ? text + ' ' : '') + '(' + notes.join(', ') + ')';
    }
    var upgrade = r.fromUpgrade ? ' (' + FROM_UPGRADE + ')' : '';
    if (r.constraint) return r.constraint + upgrade;
    if (r.columns.length) return r.columns.join(', ') + (r.refColumns.length && !sameList(r.columns, r.refColumns) ? ' → ' + r.refColumns.join(', ') : '') + upgrade;
    return 'FK' + upgrade;
  }

  function entityLabel(t, id, showComments) {
    if (t.attrs.declared === false) return t.name + ' (DDL 없음)';
    if (t.kind === 'DB_VIEW') return t.name + ' (VIEW)';
    var comment = showComments && t.attrs.comment ? String(t.attrs.comment).trim() : '';
    if (comment.length > MAX_COMMENT) comment = comment.substring(0, MAX_COMMENT) + '…';
    if (comment) return t.name + ' (' + comment + ')';
    return id !== t.name ? t.name : '';
  }

  /**
   * 범위의 관계를 그리는 모양으로 바꾼다(표준 산출물 erd.mmd의 관계 줄과 같은 규칙).
   * [{ relation, parentId, childId, parentMarker(`||`, `|o`, `}o`), childMarker(`o{`, `o|`), dashed(추론이면 true), label }]
   * 방향 미상(relation.directionKnown=false)과 방향만 아는 관계(relation.cardinalityUnknown)는 둘 다 `}o`·`o{`이고 라벨과 relation의 표시로 갈린다.
   */
  function relationLines(index, scope, columnsById) {
    var inScope = new Set(scope.tables.map(function (t) { return t.id; }));
    var columns = columnsById || new Map();
    var out = [];
    scope.relations.forEach(function (r) {
      if (!inScope.has(r.from) || !inScope.has(r.to)) return;
      var childNode = index.node(r.from);
      if (!columns.has(r.from)) columns.set(r.from, childNode ? columnInfo(index, childNode) : []);
      var m = markers(r, columns.get(r.from), childNode ? uniqueKeysOf(childNode) : []);
      out.push({ relation: r, parentId: r.to, childId: r.from, parentMarker: m.parent, childMarker: m.child, dashed: r.source === 'INFERRED', label: relationLabel(r) });
    });
    return out;
  }

  /**
   * 범위를 Mermaid erDiagram 소스로 만든다.
   * opts.columns: 'all'(기본), 'keys'(PK·FK만), 'none'. opts.comments: 테이블·컬럼 주석을 적는다(기본 true).
   * 관계는 부모 ||--o{ 자식. DDL은 실선(--), 추론은 점선(..)이고 부모 쪽이 0..1이다(|o..o{).
   * 결과: { source, aliases(Map: Mermaid 엔터티 ID → 노드 ID), undirected(방향 미상인 관계의 순번. 소스에 적힌 관계 줄의 0부터 센 차례),
   *        cardinalityUnknown(방향은 알고 카디널리티는 모르는 추론 관계의 순번. 끝 표시를 지우지 않으므로 undirected에는 들지 않는다) }
   */
  function toMermaid(index, scope, opts) {
    var o = opts || {};
    var mode = o.columns || 'all';
    var showComments = o.comments !== false;
    var ids = entityIds(scope.tables);
    var aliases = new Map();
    var lines = ['erDiagram'];
    var anyUndeclared = false;
    var anyView = false;
    var anyOutside = false;
    var columnsById = new Map();

    scope.tables.forEach(function (t) {
      var id = ids.get(t.id);
      aliases.set(id, t.id);
      var undeclared = t.attrs.declared === false;
      var view = !undeclared && t.kind === 'DB_VIEW';
      // 영역 보기에서 관계로 이어진 다른 영역의 테이블: 컬럼 없이 이름만 그린다(표준 산출물의 영역 다이어그램과 같다).
      var outside = !!(scope.outside && scope.outside.has(t.id));
      if (undeclared) anyUndeclared = true;
      if (view) anyView = true;
      if (outside && !undeclared && !view) anyOutside = true;
      var label = outside && !undeclared && !view ? t.name + ' (' + OUTSIDE_AREA + ')' : entityLabel(t, id, showComments);
      var head = '  ' + id + (label ? '["' + cleanQuoted(label) + '"]' : '') + (undeclared ? ':::undeclared' : view ? ':::view' : outside ? ':::outside' : '');
      var columns = columnInfo(index, t);
      columnsById.set(t.id, columns);
      var body = [];
      if (mode !== 'none' && !outside) {
        var used = new Set();
        columns.forEach(function (c, i) {
          var keys = [];
          if (c.pk) keys.push('PK');
          if (c.fk) keys.push('FK');
          if (c.unique && !c.pk) keys.push('UK');
          if (mode === 'keys' && !c.pk && !c.fk) return;
          var word = attributeWord(c.name, 'col' + (i + 1));
          var name = word;
          for (var k = 2; used.has(name); k++) name = word + '_' + k;
          used.add(name);
          var line = '    ' + attributeWord(c.node.attrs.dataType, 'unknown') + ' ' + name;
          if (keys.length) line += ' ' + keys.join(', ');
          var notes = [];
          if (undeclared) notes.push(REFERENCED_COLUMN);
          if (showComments && c.node.attrs.comment) notes.push(cleanQuoted(c.node.attrs.comment).trim());
          var comment = notes.filter(Boolean).join(', ');
          if (comment.length > MAX_COMMENT) comment = comment.substring(0, MAX_COMMENT) + '…';
          if (comment) line += ' "' + comment + '"';
          body.push(line);
        });
      }
      if (body.length) lines.push(head + ' {', body.join('\n'), '  }');
      else lines.push(head);
    });

    var undirected = [];
    var cardinalityUnknown = [];
    var drawn = 0;
    relationLines(index, scope, columnsById).forEach(function (line) {
      var child = ids.get(line.childId);
      var parent = ids.get(line.parentId);
      if (!child || !parent) return;
      lines.push('  ' + parent + ' ' + line.parentMarker + (line.dashed ? '..' : '--') + line.childMarker + ' ' + child + ' : "' + cleanQuoted(line.label) + '"');
      if (!line.relation.directionKnown) undirected.push(drawn);
      else if (line.relation.cardinalityUnknown) cardinalityUnknown.push(drawn);
      drawn++;
    });
    // Mermaid는 classDef 값의 공백을 지운다("6 4"가 "64"가 된다). 공백 없는 값만 쓰고, 정확한 점선은 뷰어가 그린 뒤에 입힌다(ui/diagram.js).
    if (anyUndeclared) lines.push('  classDef undeclared stroke-dasharray:5');
    if (anyView) lines.push('  classDef view stroke-width:2px');
    if (anyOutside) lines.push('  classDef outside stroke-dasharray:2');
    return { source: lines.join('\n') + '\n', aliases: aliases, undirected: undirected, cardinalityUnknown: cardinalityUnknown };
  }

  var api = {
    DEFAULT_MAX_NODES: DEFAULT_MAX_NODES,
    DEFAULT_MAX_CHARS: DEFAULT_MAX_CHARS,
    MAX_AREA_TABLES: MAX_AREA_TABLES,
    LEGEND: LEGEND,
    selectScope: selectScope,
    exportModel: exportModel,
    exportMermaid: exportMermaid,
    splitAreas: splitAreas,
    areaPrefix: areaPrefix,
    parseCardinality: parseCardinality,
    fixedText: fixedText,
    relationLabel: relationLabel,
    relationLines: relationLines,
    toMermaid: toMermaid
  };
  CA.erd = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
