// 노드 상세: 꼬리표, 요약, 속성, 소스 위치, 들어오고 나가는 엣지와 신뢰도.
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var G = CA.graph;
  var ui = CA.ui;
  var h = ui.h;
  var PAGE = 50;

  // 엣지 줄 오른쪽에 보여 줄 속성. 나머지는 줄을 펼치면 나온다.
  var EDGE_ATTRS = ['callType', 'resolution', 'op', 'access', 'field', 'multiplicity', 'via', 'qualifier', 'role', 'on', 'source', 'constraint',
    'columns', 'refColumns', 'cardinality', 'directionKnown', 'evidenceCount', 'leftColumns', 'rightColumns', 'viewName', 'strippedPrefix', 'text',
    'argCount', 'viaProp', 'path', 'receiverType', 'namespace', 'typeArguments', 'narrowedBy', 'module'];

  /** 엣지 줄 오른쪽 끝의 글자. 한 줄 요약이 있으면 속성을 다시 늘어놓지 않고 위치만 적는다(속성은 줄을 펼치면 모두 나온다). */
  function edgeExtra(item) {
    var parts = [];
    if (!item.summary) item.attrs.forEach(function (a) {
      if (EDGE_ATTRS.indexOf(a.key) >= 0 && a.value !== '') parts.push(a.key + '=' + a.value);
    });
    if (item.location) parts.push(item.location);
    return parts.join('  ');
  }

  /** 속성 값. 노드 ID인 값은 그 노드로 가는 링크로, 나머지는 글자로. 목록은 쉼표로 잇는다. */
  function attrValue(attr, follow) {
    var parts = attr.items.map(function (it) {
      if (!it.nodeId) return it.text;
      return h('button', { class: 'link', type: 'button', text: it.text, title: '이 노드로 이동', on: { click: function (e) { e.stopPropagation(); follow(it.nodeId); } } });
    });
    // 값 안에 쉼표가 있는 목록(indexes의 `IX1(A,B DESC)`)은 한 줄에 하나씩 보인다.
    if (attr.multiline) return [h('div', { class: 'multi' }, parts.map(function (p) { return h('span', { class: 'item' }, [p]); }))];
    var out = [];
    parts.forEach(function (p, i) {
      if (i > 0) out.push(', ');
      out.push(p);
    });
    return out;
  }

  function attrTable(attrs, follow) {
    var table = h('dl', { class: 'kv' });
    attrs.forEach(function (a) {
      table.appendChild(h('dt', { text: a.key }));
      table.appendChild(h('dd', { class: a.key === 'sql' ? 'sql' : '' }, attrValue(a, follow)));
    });
    return table;
  }

  function edgeSection(title, groups, follow) {
    var total = groups.reduce(function (s, g) { return s + g.items.length; }, 0);
    var section = h('div', { class: 'section' }, [h('h3', {}, [title, h('span', { class: 'n', text: ui.formatCount(total) })])]);
    if (!total) {
      section.appendChild(h('div', { class: 'hint', text: '없음' }));
      return section;
    }
    groups.forEach(function (g) {
      var box = h('div', { class: 'edge-group' }, [
        h('div', { class: 'edge-kind' }, [g.kind, h('span', { class: 'muted', text: ui.formatCount(g.items.length) })])
      ]);
      var shown = 0;
      var more = null;
      function showMore() {
        var until = Math.min(g.items.length, shown + PAGE);
        for (; shown < until; shown++) box.insertBefore(edgeRow(g.items[shown], follow), more);
        if (more && shown >= g.items.length) { more.remove(); more = null; }
        else if (more) more.firstChild.textContent = '더 보기 (' + ui.formatCount(g.items.length - shown) + '개 남음)';
      }
      if (g.items.length > PAGE) {
        more = h('div', { class: 'edge-more' }, [h('button', { class: 'btn small', type: 'button', text: '', on: { click: showMore } })]);
        box.appendChild(more);
      }
      showMore();
      section.appendChild(box);
    });
    return section;
  }

  /**
   * 컬럼 쌍이 여럿인 JOINS·REFERENCES의 쌍 표. 첫 줄은 양쪽 테이블 이름이고, 그 아래로 쌍마다 한 줄씩 `왼쪽 컬럼  =  오른쪽 컬럼`을 맞춰 적는다.
   * 한 줄에 `A.X = B.Y AND A.Z = B.W AND …`로 늘어놓으면 잘려 보이지 않고, `X, Z → Y, W`는 위치를 세어 맞춰야 해서 따로 보인다.
   */
  function pairTable(p) {
    var head = function (name, note) { return name + (note ? ' (' + note + ')' : ''); };
    var rows = [h('div', { class: 'pair head' }, [h('span', { text: head(p.left, p.leftNote) }), h('span', { text: '' }), h('span', { text: head(p.right, p.rightNote) })])];
    p.rows.forEach(function (r) {
      rows.push(h('div', { class: 'pair' }, [h('span', { text: r[0] }), h('span', { class: 'op', text: p.op }), h('span', { text: r[1] })]));
    });
    return h('div', { class: 'edge-pairs' }, rows);
  }

  /** 엣지 한 줄. 속성이 있으면 ▸를 눌러 모든 속성과 위치를 펼친다(추론 관계의 근거 구문, 주입 후보 등). */
  function edgeRow(item, follow) {
    var extra = edgeExtra(item);
    var details = null;
    var toggle = h('button', { class: 'edge-toggle' + (item.attrs.length ? '' : ' leaf'), type: 'button', text: '▸', attrs: { 'aria-label': '엣지 속성 펼치기', 'aria-expanded': 'false' } });
    var line = h('div', { class: 'edge-row' }, [
      toggle,
      ui.confBadge(item.confidence, true),
      item.other ? ui.kindBadge(item.other.kind) : h('span', { class: 'tag warn', text: '노드 없음', title: '그래프에 이 ID의 노드가 없다' }),
      h('button', { class: 'link', type: 'button', text: item.label, title: item.otherId, on: { click: function () { follow(item.otherId); } } }),
      item.summary ? h('span', { class: 'edge-summary', text: item.summary, title: item.fullSummary || item.summary }) : null,
      extra ? h('span', { class: 'extra', text: extra, title: extra }) : null
    ]);
    var wrap = h('div', { class: 'edge-item' }, item.pairs ? [line, pairTable(item.pairs)] : [line]);
    toggle.addEventListener('click', function () {
      if (!item.attrs.length) return;
      if (details) { details.remove(); details = null; }
      else {
        details = h('div', { class: 'edge-attrs' }, [attrTable(item.attrs, follow)]);
        if (item.location) details.appendChild(h('div', { class: 'edge-loc', text: '위치 ' + item.location }));
        wrap.appendChild(details);
      }
      toggle.textContent = details ? '▾' : '▸';
      toggle.setAttribute('aria-expanded', String(!!details));
    });
    return wrap;
  }

  /**
   * container에 노드 상세를 그린다.
   * opts.inDrawer: 서랍 안이면 "탐색에서 열기" 단추를 붙인다.
   * opts.follow(id): 상세 안의 노드 링크(엣지의 상대, 속성 값의 노드)를 눌렀을 때 부른다. 없으면 app.showDetail.
   */
  function renderDetail(container, app, id, opts) {
    var o = opts || {};
    var follow = o.follow || function (to) { app.showDetail(to); };
    var index = app.index;
    var d = G.nodeDetail(index, id);
    if (!d) {
      container.replaceChildren(h('div', { class: 'empty' }, [
        h('div', { text: '그래프에 이 ID의 노드가 없다.' }),
        h('div', { class: 'mono', text: id })
      ]));
      return;
    }
    var node = d.node;
    var meta = G.kindMeta(node.kind);
    var tags = [ui.kindBadge(node.kind), h('span', { class: 'muted', text: meta.label })];
    d.tags.forEach(function (t) { tags.push(h('span', { class: 'tag' + (t.warn ? ' warn' : ''), text: t.text, title: t.title })); });

    var actions = [];
    function action(text, view, params) {
      actions.push(h('button', { class: 'btn small', type: 'button', text: text, on: { click: function () { app.navigate(view, params); } } }));
    }
    if (CA.calltree.ROOT_KINDS.indexOf(node.kind) >= 0) action('콜트리', 'calltree', { id: id });
    if (node.kind === 'JAVA_TYPE') action('클래스 다이어그램', 'classdiagram', { type: id });
    // 필터·인터셉터는 요청 처리의 진입점이다: 진입 메서드(doFilter, preHandle 등)의 콜트리로 바로 간다.
    if (node.kind === 'JAVA_TYPE' && (node.attrs.stereotype === 'filter' || node.attrs.stereotype === 'interceptor')) {
      CA.calltree.filterEntries(index).forEach(function (entry) {
        if (entry.typeId === id) action('콜트리: ' + entry.node.name, 'calltree', { id: entry.id });
      });
    }
    if (node.kind === 'JAVA_PACKAGE') action('클래스 다이어그램', 'classdiagram', { package: node.qualifiedName });
    if (node.kind === 'DB_TABLE' || node.kind === 'DB_VIEW') action('ERD', 'erd', { table: id });
    if (o.inDrawer) action('탐색에서 열기', 'explore', { id: id });

    var head = h('div', { class: 'detail-head' }, [
      h('div', { class: 'detail-tags' }, tags),
      h('h2', { text: node.kind === 'HTTP_REQUEST' ? G.requestLabel(node) : node.qualifiedName || node.name }),
      d.summary ? h('div', { class: 'summary', text: d.summary }) : null,
      h('div', { class: 'id', text: node.id }),
      d.location ? h('div', { class: 'loc', text: d.location, title: '소스 위치 (프로젝트 루트 기준)' }) : h('div', { class: 'loc muted', text: '소스 위치 없음' }),
      actions.length ? h('div', { class: 'detail-actions' }, actions) : null
    ]);

    var attrs = h('div', { class: 'section' }, [
      h('h3', {}, ['속성', h('span', { class: 'n', text: String(d.attrs.length) })]),
      d.attrs.length ? attrTable(d.attrs, follow) : h('div', { class: 'hint', text: '없음' }),
      // 분석기가 링커에 넘기려고 남긴 중간 정보(메서드 본문의 구문 ID 문자열, 폼 필드 등)는 접어 둔다.
      d.internalAttrs && d.internalAttrs.length ? h('details', { class: 'internal-attrs' }, [
        h('summary', {}, ['분석 중간 정보', h('span', { class: 'n', text: String(d.internalAttrs.length) })]),
        h('div', { class: 'hint', text: '분석기가 다음 단계(링커)에 넘기려고 남긴 값이다. 관계는 이미 엣지로 만들어져 있다.' }),
        attrTable(d.internalAttrs, follow)
      ]) : null
    ]);

    // 테이블·뷰: DDL에 없는데 SQL이 참조하는 컬럼(정의가 있는 테이블의 declared=false 컬럼)을 구문과 함께 따로 보인다.
    // ERD에는 넣지 않는 컬럼이다(표준 산출물의 erd/tables.md "DDL에 없는데 SQL이 참조하는 컬럼", report.md "SQL과 DDL의 컬럼 불일치").
    var ghosts = null;
    if (d.undeclaredColumns && d.undeclaredColumns.length) {
      ghosts = h('div', { class: 'section undeclared-columns' }, [
        h('h3', {}, ['DDL에 없는데 SQL이 참조하는 컬럼', h('span', { class: 'n', text: String(d.undeclaredColumns.length) })]),
        h('div', { class: 'hint', text: 'SQL은 참조하지만 이 테이블의 DDL에 없는 컬럼이다. DDL이 오래됐거나 다른 방언·버전의 DDL이 선택됐을 수 있다. ERD에는 넣지 않는다.' })
      ].concat(d.undeclaredColumns.map(function (c) {
        var parts = [
          h('button', { class: 'link name', type: 'button', text: c.name, title: c.id, on: { click: function () { follow(c.id); } } }),
          h('span', { class: 'muted', text: ' — 구문 ' + c.statements.length + '개' + (c.statements.length ? ': ' : '') })
        ];
        c.statements.forEach(function (st, i) {
          if (i > 0) parts.push(', ');
          parts.push(h('button', { class: 'link', type: 'button', text: st.label, title: st.id, on: { click: function () { follow(st.id); } } }));
        });
        return h('div', { class: 'undeclared-column' }, parts);
      })));
    }

    container.replaceChildren(h('div', { class: 'detail-body' }, [
      head, attrs, ghosts,
      edgeSection('나가는 엣지', d.outgoing, follow),
      edgeSection('들어오는 엣지', d.incoming, follow)
    ]));
    container.scrollTop = 0;
  }

  ui.renderDetail = renderDetail;
})(typeof globalThis !== 'undefined' ? globalThis : this);
