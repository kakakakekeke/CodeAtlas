// 클래스 다이어그램: 범위 선택과 Mermaid 소스 생성. DOM을 쓰지 않는다.
(function (root) {
  'use strict';
  if (typeof module === 'object' && module.exports) require('./graph.js');
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var G = CA.graph;

  var RELATION_KINDS = ['EXTENDS', 'IMPLEMENTS', 'ASSOCIATES', 'DEPENDS_ON'];
  var DEFAULT_MAX_NODES = 60;
  var MAX_MEMBERS = 30;
  var TYPE_PREFIX = 'java:type:';

  /** 타입 노드의 패키지 이름. ID의 바이너리 이름에서 마지막 '.' 앞부분이다. */
  function packageOf(node) {
    var bin = node.id.indexOf(TYPE_PREFIX) === 0 ? node.id.substring(TYPE_PREFIX.length) : (node.qualifiedName || '');
    var i = bin.lastIndexOf('.');
    return i < 0 ? '' : bin.substring(0, i);
  }

  /** 소스가 없는 타입(외부 라이브러리이거나 참조만 된 타입). */
  function isExternal(type) {
    return type.attrs.external === true || type.attrs.declared === false;
  }

  function isAnonymousOrLocal(type) {
    return type.attrs.anonymous === true || type.attrs.local === true;
  }

  /**
   * 타입이 하나라도 있는 패키지와 타입 수. external은 그 패키지의 타입이 모두 소스 없는 타입이라는 뜻이다.
   * 소스가 있는 패키지가 먼저, 그 안에서 이름 순.
   */
  function packages(index) {
    var counts = new Map();
    index.nodesOfKind('JAVA_TYPE').forEach(function (t) {
      var p = packageOf(t);
      var c = counts.get(p);
      if (!c) counts.set(p, c = { name: p, count: 0, external: true });
      c.count++;
      if (!isExternal(t)) c.external = false;
    });
    var out = Array.from(counts.values());
    out.sort(function (a, b) { return (a.external ? 1 : 0) - (b.external ? 1 : 0) || G.compareText(a.name, b.name); });
    return out;
  }

  /**
   * opts.packages: 패키지 이름 배열. opts.includeSubpackages: 하위 패키지도 넣는다.
   * opts.types: 타입 노드 ID 배열. opts.depth: 선택한 타입에서 관계를 따라 넓힐 단계 수(기본 0).
   * opts.kinds: 그릴 관계 종류(기본 네 가지 전부). opts.maxNodes: 타입 수 상한.
   * opts.includeExternal: 소스 없는 타입도 넣는다(기본 true). opts.includeAnonymous: 익명·로컬 타입도 넣는다(기본 false).
   * opts.includeSupertypes: 범위 안 타입이 상속·구현하는 타입은 범위 밖이어도 넣는다(기본 false).
   * opts.types로 직접 고른 타입은 위 필터와 상관없이 넣는다.
   * 결과: { types, relations(범위 안 타입끼리의 엣지), outside(상위 타입이라서 들어온 범위 밖 타입의 ID 집합), total(상한 적용 전 타입 수), truncated }
   */
  function selectScope(index, opts) {
    var o = opts || {};
    var kinds = new Set(o.kinds || RELATION_KINDS);
    var max = o.maxNodes > 0 ? o.maxNodes : DEFAULT_MAX_NODES;
    var includeExternal = o.includeExternal !== false;
    var includeAnonymous = o.includeAnonymous === true;
    var picked = [];
    var seen = new Set();

    function pick(node, explicit) {
      if (!node || node.kind !== 'JAVA_TYPE' || seen.has(node.id)) return false;
      if (!explicit) {
        if (!includeExternal && isExternal(node)) return false;
        if (!includeAnonymous && isAnonymousOrLocal(node)) return false;
      }
      seen.add(node.id);
      picked.push(node);
      return true;
    }

    // 선택한 타입과 그 이웃을 먼저 넣어 상한에 걸려도 가까운 것이 남게 한다.
    var frontier = [];
    (o.types || []).forEach(function (id) {
      var n = index.node(id);
      if (pick(n, true)) frontier.push(n);
    });
    for (var d = 0; d < (o.depth || 0) && frontier.length; d++) {
      var next = [];
      frontier.forEach(function (t) {
        index.out(t.id).forEach(function (e) {
          if (kinds.has(e.kind)) { var n = index.node(e.to); if (pick(n)) next.push(n); }
        });
        index.in(t.id).forEach(function (e) {
          if (kinds.has(e.kind)) { var n = index.node(e.from); if (pick(n)) next.push(n); }
        });
      });
      frontier = next;
    }

    var pkgs = o.packages || [];
    if (pkgs.length) {
      var inPackage = index.nodesOfKind('JAVA_TYPE').filter(function (t) {
        var p = packageOf(t);
        return pkgs.some(function (want) {
          return p === want || (o.includeSubpackages && (want === '' || p.indexOf(want + '.') === 0));
        });
      });
      inPackage.sort(function (a, b) { return G.compareText(a.qualifiedName, b.qualifiedName); });
      inPackage.forEach(function (t) { pick(t); });
    }

    // 범위 안 타입이 상속·구현하는 타입(제네릭 상위 클래스, 프레임워크 인터페이스)을 한 단계 붙인다.
    var outside = new Set();
    if (o.includeSupertypes) {
      picked.slice().forEach(function (t) {
        index.out(t.id).forEach(function (e) {
          if ((e.kind === 'EXTENDS' || e.kind === 'IMPLEMENTS') && kinds.has(e.kind) && pick(index.node(e.to))) outside.add(e.to);
        });
      });
    }

    var total = picked.length;
    var types = total > max ? picked.slice(0, max) : picked;
    var inScope = new Set(types.map(function (t) { return t.id; }));
    outside.forEach(function (id) { if (!inScope.has(id)) outside.delete(id); });
    var relations = [];
    types.forEach(function (t) {
      index.out(t.id).forEach(function (e) {
        if (kinds.has(e.kind) && inScope.has(e.to)) relations.push(e);
      });
    });
    return { types: types, relations: relations, outside: outside, total: total, truncated: total > max };
  }

  // ---------- Mermaid

  /** 패키지 접두사를 떼고 Mermaid가 받는 글자만 남긴다. 제네릭 <T>는 ~T~로 쓴다. */
  function simpleType(type) {
    if (!type) return '';
    return String(type)
      .replace(/(?:[A-Za-z_$][\w$]*\.)+(?=[A-Za-z_$])/g, '')
      .replace(/[<>]/g, '~')
      .replace(/[{}"`\n\r]/g, '');
  }

  // 라벨에 < > 를 그대로 두면 Mermaid가 HTML 태그로 보고 그 사이의 글자까지 지운다(BaseService<T> → BaseService). 엔터티로 적는다.
  function cleanLabel(text) {
    return String(text).replace(/"/g, "'").replace(/[`\n\r]/g, ' ').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  /** 다이어그램에 적는 타입 이름: 중첩 타입은 바깥 이름과 함께(Outer.Inner), 제네릭 타입은 타입 매개변수와 함께(Base<T extends X>). */
  function typeLabel(type) {
    var name = type.name;
    if (type.id.indexOf(TYPE_PREFIX) === 0) {
      var bin = type.id.substring(TYPE_PREFIX.length);
      name = bin.substring(bin.lastIndexOf('.') + 1).replace(/\$(?=[^0-9])/g, '.');
    }
    var params = type.attrs.typeParameters;
    return name + (Array.isArray(params) && params.length ? '<' + params.join(', ') + '>' : '');
  }

  function cleanWord(text) {
    return String(text).replace(/[<>]/g, '').replace(/[{}"`\n\r:]/g, '');
  }

  function has(list, value) {
    return Array.isArray(list) && list.indexOf(value) >= 0;
  }

  /** 수식어가 없으면 package-private(~)이지만, 인터페이스와 애너테이션의 멤버는 public이다. */
  function visibility(modifiers, owner) {
    if (has(modifiers, 'public')) return '+';
    if (has(modifiers, 'private')) return '-';
    if (has(modifiers, 'protected')) return '#';
    var kind = owner && owner.attrs.typeKind;
    return kind === 'interface' || kind === 'annotation' ? '+' : '~';
  }

  function parameterTypes(method) {
    if (Array.isArray(method.attrs.parameterTypes)) return method.attrs.parameterTypes;
    var open = method.id.lastIndexOf('(');
    var close = method.id.lastIndexOf(')');
    if (open < 0 || close <= open + 1) return [];
    return method.id.substring(open + 1, close).split(',');
  }

  function fieldLine(f, owner) {
    var a = f.attrs;
    var type = simpleType(a.type);
    return visibility(a.modifiers, owner) + (type ? type + ' ' : '') + cleanWord(f.name) + (has(a.modifiers, 'static') ? '$' : '');
  }

  function methodLine(m, owner) {
    var a = m.attrs;
    var isCtor = a.constructor === true || m.name === '<init>';
    var name = isCtor ? cleanWord(owner.name) : cleanWord(m.name);
    if (!name) name = '_';
    var params = parameterTypes(m).map(simpleType).join(', ');
    var classifier = has(a.modifiers, 'abstract') ? '*' : has(a.modifiers, 'static') ? '$' : '';
    var ret = isCtor ? '' : simpleType(a.returnType);
    return visibility(a.modifiers, owner) + name + '(' + params + ')' + classifier + (ret ? ' ' + ret : '');
  }

  function startLine(n) {
    return n.location && n.location.startLine > 0 ? n.location.startLine : Number.MAX_SAFE_INTEGER;
  }

  function memberLines(index, type, showSynthetic) {
    var fields = [];
    var methods = [];
    index.out(type.id).forEach(function (e) {
      if (e.kind !== 'CONTAINS') return;
      var m = index.node(e.to);
      if (!m) return;
      // 합성 멤버(기본 생성자, <clinit>, record·enum의 자동 멤버, Lombok이 만들 멤버)는 소스에 없다. 고를 때만 보인다.
      if (!showSynthetic && m.attrs.synthetic === true) return;
      if (m.kind === 'JAVA_FIELD') fields.push(m);
      else if (m.kind === 'JAVA_METHOD') methods.push(m);
    });
    var byLine = function (a, b) { return startLine(a) - startLine(b) || G.compareText(a.id, b.id); };
    fields.sort(byLine);
    methods.sort(byLine);
    var lines = fields.map(function (f) { return fieldLine(f, type); }).concat(methods.map(function (m) { return methodLine(m, type); }));
    if (lines.length > MAX_MEMBERS) {
      var rest = lines.length - MAX_MEMBERS;
      lines = lines.slice(0, MAX_MEMBERS);
      lines.push('... 외 ' + rest + '개');
    }
    return lines;
  }

  function annotation(type) {
    var a = type.attrs;
    if (a.typeKind && a.typeKind !== 'class') return cleanWord(a.typeKind);
    if (a.anonymous === true) return 'anonymous';
    if (a.stereotype) return cleanWord(a.stereotype);
    if (has(a.modifiers, 'abstract')) return 'abstract';
    return '';
  }

  /**
   * 범위를 Mermaid classDiagram 소스로 만든다.
   * opts.showMembers: 필드와 메서드를 넣는다. opts.showSynthetic: 합성 멤버(synthetic=true)도 넣는다. opts.direction: TB(기본) 또는 LR.
   * 결과: { source, aliases(Map: Mermaid 별칭 → 노드 ID) }
   */
  function toMermaid(index, scope, opts) {
    var o = opts || {};
    var aliases = new Map();
    var aliasOf = new Map();
    var nameCount = new Map();
    var labels = new Map();
    scope.types.forEach(function (t, i) {
      aliases.set('c' + i, t.id);
      aliasOf.set(t.id, 'c' + i);
      var label = typeLabel(t);
      labels.set(t.id, label);
      nameCount.set(label, (nameCount.get(label) || 0) + 1);
    });

    var lines = ['classDiagram', '  direction ' + (o.direction === 'LR' ? 'LR' : 'TB')];
    // classDiagram 문법은 classDef의 첫 속성 이름에 '-'가 있으면 받지 못해 stroke를 앞에 둔다.
    // Mermaid는 클래스 도형에 stroke-dasharray를 입히지 않으므로 점선은 뷰어가 그린 뒤에 external 도형에 직접 입힌다(ui/diagram.js).
    if (scope.types.some(isExternal)) lines.push('  classDef external stroke:#888888,stroke-dasharray:4 3');

    scope.types.forEach(function (t) {
      // 같은 이름의 타입이 둘 이상이면 패키지를 붙여 구분한다.
      var label = labels.get(t.id);
      if (nameCount.get(label) > 1 && packageOf(t)) label = packageOf(t) + '.' + label;
      var head = '  class ' + aliasOf.get(t.id) + '["' + cleanLabel(label) + '"]' + (isExternal(t) ? ':::external' : '');
      var body = [];
      var ann = annotation(t);
      if (ann) body.push('    <<' + ann + '>>');
      if (o.showMembers) memberLines(index, t, o.showSynthetic === true).forEach(function (m) { body.push('    ' + m); });
      if (body.length) lines.push(head + ' {', body.join('\n'), '  }');
      else lines.push(head);
    });

    // 같은 쌍·같은 종류의 관계는 한 선으로 합친다.
    var merged = new Map();
    scope.relations.forEach(function (e) {
      if (!aliasOf.has(e.from) || !aliasOf.has(e.to)) return;
      var key = e.kind + '\u0000' + e.from + '\u0000' + e.to;
      var m = merged.get(key);
      if (!m) merged.set(key, m = { kind: e.kind, from: aliasOf.get(e.from), to: aliasOf.get(e.to), fields: [], many: false });
      if (e.attrs && e.attrs.field && m.fields.indexOf(e.attrs.field) < 0) m.fields.push(e.attrs.field);
      if (e.attrs && e.attrs.multiplicity === '*') m.many = true;
    });
    merged.forEach(function (m) {
      if (m.kind === 'EXTENDS') lines.push('  ' + m.to + ' <|-- ' + m.from);
      else if (m.kind === 'IMPLEMENTS') lines.push('  ' + m.to + ' <|.. ' + m.from);
      else if (m.kind === 'ASSOCIATES') {
        lines.push('  ' + m.from + ' --> ' + (m.many ? '"*" ' : '') + m.to + (m.fields.length ? ' : ' + m.fields.map(cleanWord).join(', ') : ''));
      } else if (m.kind === 'DEPENDS_ON') lines.push('  ' + m.from + ' ..> ' + m.to);
    });
    return { source: lines.join('\n') + '\n', aliases: aliases };
  }

  var api = {
    RELATION_KINDS: RELATION_KINDS,
    DEFAULT_MAX_NODES: DEFAULT_MAX_NODES,
    packageOf: packageOf,
    packages: packages,
    selectScope: selectScope,
    simpleType: simpleType,
    typeLabel: typeLabel,
    isExternal: isExternal,
    toMermaid: toMermaid
  };
  CA.classDiagram = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
