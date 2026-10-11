// ERD 화면: 테이블 전체 또는 선택한 테이블과 이웃을 그린다.
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var ERD = CA.erd;
  var ui = CA.ui;
  var h = ui.h;
  var doc = root.document;
  var MAX_LIMIT = 300;
  var SVG_NS = 'http://www.w3.org/2000/svg'; // XML 이름공간 식별자. 네트워크 주소로 쓰이지 않는다.
  CA.views = CA.views || {};

  function svgEl(tag, attrs) {
    var el = doc.createElementNS(SVG_NS, tag);
    Object.keys(attrs).forEach(function (k) { el.setAttribute(k, attrs[k]); });
    return el;
  }

  /** 범례 한 줄의 작은 그림: 실선, 점선(끝에 카디널리티 표시가 있거나, 없거나, 양쪽 다 가장 약한 표시이거나), 점선 상자. */
  function legendSample(item) {
    var svg = svgEl('svg', { width: '44', height: '16', viewBox: '0 0 44 16', 'aria-hidden': 'true' });
    function stroke(tag, attrs) {
      attrs.stroke = 'currentColor';
      attrs['stroke-width'] = '1.5';
      attrs.fill = 'none';
      var el = svgEl(tag, attrs);
      svg.appendChild(el);
      return el;
    }
    if (item.line) {
      var line = stroke('line', { x1: '2', y1: '8', x2: '42', y2: '8' });
      if (item.line === 'dashed') line.setAttribute('stroke-dasharray', '4 3');
      if (item.ends === 'weak') {
        // 카디널리티 모름: 양쪽 다 가장 약한 표시(0개 이상) — 동그라미와 까마귀발. 어느 쪽에도 "하나" 표시(세로 금)를 두지 않는다.
        stroke('path', { d: 'M9 8l-7 -4M9 8l-7 4' });
        stroke('circle', { cx: '12', cy: '8', r: '2.5' });
        stroke('circle', { cx: '32', cy: '8', r: '2.5' });
        stroke('path', { d: 'M35 8l7 -4M35 8l7 4' });
      } else if (item.ends === 'optional') {
        // 추론 관계: 부모 쪽(0 또는 1)은 세로 금 하나와 동그라미, 자식 쪽(여럿)은 까마귀발.
        stroke('path', { d: 'M5 4v8' });
        stroke('circle', { cx: '10', cy: '8', r: '2.5' });
        stroke('path', { d: 'M35 8l7 -4M35 8l7 4' });
      } else if (item.ends) {
        // 부모 쪽(하나): 세로 금 둘. 자식 쪽(여럿): 까마귀발.
        stroke('path', { d: 'M6 4v8M9 4v8' });
        stroke('path', { d: 'M35 8l7 -4M35 8l7 4' });
      }
    } else {
      var rect = stroke('rect', { x: '6', y: '2', width: '32', height: '12' });
      if (item.box === 'dashed') rect.setAttribute('stroke-dasharray', '4 3');
    }
    return svg;
  }

  CA.views.erd = {
    title: 'ERD',
    create: function (app) {
      var index = app.index;
      var state = { mode: 'all', area: null, tables: [], depth: 1, includeInferred: true, includeViews: false, comments: true, columns: 'all', maxNodes: ERD.DEFAULT_MAX_NODES };
      var drawn = false;

      var diagram = ui.createDiagram({ fileName: 'erd', onNodeClick: function (id) { app.showDetail(id); } });
      var summary = h('span', { class: 'muted dg-summary' });
      var warning = h('div', { class: 'banner', attrs: { style: 'display:none' } });
      var areasBox = h('div', { class: 'erd-areas', attrs: { style: 'display:none' } });

      /** 영역 목록(테이블 이름 접두사 묶음). 표준 산출물이 큰 ERD를 나눌 때(erd/index.md)와 같은 규칙이고, 지금의 표시 선택을 따른다. */
      function areas() {
        return ERD.splitAreas(index, { includeInferred: state.includeInferred, includeViews: state.includeViews, comments: state.comments, columns: state.columns });
      }
      function renderAreas() {
        areasBox.style.display = state.mode === 'area' ? '' : 'none';
        if (state.mode !== 'area') return;
        var split = areas();
        if (!split.areas.some(function (a) { return a.name === state.area; })) state.area = split.areas.length ? split.areas[0].name : null;
        var rows = split.areas.map(function (a) {
          return h('button', { class: 'area-row', type: 'button', attrs: { 'aria-pressed': String(a.name === state.area), 'data-area': a.name },
            title: a.label + ' · 테이블 ' + a.tables.length + '개 · 영역 안 관계 ' + a.innerRelations + '개 · 다른 영역과의 관계 ' + a.outerRelations + '개'
              + (split.hubs.length ? ' · 허브와의 관계 ' + a.hubRelations + '개' : '') + ' · 표준 산출물 erd-' + a.fileName + '.mmd (' + ui.formatCount(a.chars) + '자)',
            on: { click: function () { state.area = a.name; renderAreas(); draw(); } } },
          // 영역 이름이 된 테이블에 논리명이 있으면 `논리명(물리명)`으로 보인다(표준 산출물의 erd/index.md와 같다).
          [h('span', { class: 'name', text: a.label }), h('span', { class: 'n', text: ui.formatCount(a.tables.length) + '개' }),
            h('span', { class: 'rel', text: '관계 ' + a.innerRelations + (a.outerRelations ? ' + 밖 ' + a.outerRelations : '') + (a.hubRelations ? ' + 허브 ' + a.hubRelations : '') })]);
        });
        var note = '영역은 테이블 이름의 접두사(`_` 앞 토큰)로 묶는다. 표준 산출물이 큰 ERD를 나눌 때(erd/index.md)와 같은 규칙이다: 묶음의 다이어그램이 '
          + ui.formatCount(split.maxChars) + '자를 넘으면 둘째 토큰까지로 다시 나누고, 접두사로 묶이지 않는 테이블은 관계로 이어진 테이블끼리 묶어 그 안에서 가장 많이 참조되는 테이블의 이름을 붙이고(논리명이 있으면 논리명(물리명)), '
          + '그러고도 남는 테이블은 이름의 공통 접두사로 묶고(이름 끝에 *), 묶이지 않는 나머지만 "기타"로 합친다. 관계로 묶은 영역과 이름으로 묶은 영역은 테이블 ' + ERD.MAX_AREA_TABLES + '개를 넘지 않는다. '
          + (split.centers.length ? '중심 테이블(' + split.centers.map(function (x) { return x.name; }).join(', ') + ')은 ' + ERD.MAX_AREA_TABLES + '개를 넘는 묶음에서 가장 많이 참조되는 테이블이다: 그것을 빼고 나머지를 다시 묶었고, 중심은 자기 이름의 영역에 있으며 다른 영역에는 이름만 나온다. ' : '')
          + (split.hubs.length ? '허브 테이블(전체의 20% 넘는 테이블이 참조: ' + split.hubs.map(function (x) { return x.name + ' ' + x.referencedBy + '개'; }).join(', ') + ')은 영역에 넣지 않았고 그것을 참조하는 영역마다 이름만 나온다. 허브와의 관계는 "밖"(다른 영역과의 관계)에 넣지 않고 따로 센다. ' : '')
          + (split.oversize ? '이 그래프의 전체 ERD는 ' + ui.formatCount(split.fullChars) + '자라서 표준 산출물도 영역으로 나뉘어 있다.'
            : '이 그래프의 전체 ERD는 ' + ui.formatCount(split.fullChars) + '자라서 표준 산출물은 나누지 않았다(erd/erd.mmd 한 장).');
        areasBox.replaceChildren.apply(areasBox, rows.length ? rows.concat([h('div', { class: 'hint', text: note })]) : [h('div', { class: 'hint', text: '테이블이 없다.' })]);
      }

      function draw() {
        drawn = true;
        if (state.mode === 'pick' && !state.tables.length) {
          summary.textContent = '';
          warning.style.display = 'none';
          diagram.clear('왼쪽에서 테이블을 고르면 그 테이블과 이웃을 그린다.');
          return;
        }
        if (state.mode === 'area') renderAreas();
        var scope = ERD.selectScope(index, state.mode === 'area' ? {
          area: state.area == null ? '' : state.area, includeInferred: state.includeInferred, includeViews: state.includeViews,
          comments: state.comments, columns: state.columns
        } : {
          tables: state.mode === 'pick' ? state.tables : null, depth: state.depth,
          includeInferred: state.includeInferred, includeViews: state.includeViews, maxNodes: state.maxNodes
        });
        var inferred = scope.relations.filter(function (r) { return r.source === 'INFERRED'; });
        var unknown = inferred.filter(function (r) { return !r.directionKnown; }).length;
        // 방향(자식 → 부모)은 알지만 카디널리티는 모르는 추론 관계(directionFrom=DDL, cardinality=unknown): 방향 미상과 따로 센다.
        var noCardinality = inferred.filter(function (r) { return r.cardinalityUnknown; }).length;
        var outside = scope.outside ? scope.outside.size : 0;
        summary.textContent = (state.mode === 'area' && scope.area ? '영역 ' + scope.area.label + ' · ' : '')
          + '테이블 ' + ui.formatCount(scope.tables.length - outside) + '개' + (outside ? ' + 다른 영역의 이웃 ' + outside + '개' : '')
          + ' · 관계 ' + ui.formatCount(scope.relations.length) + '개'
          + (inferred.length ? ' (추론 ' + inferred.length + '개' + (unknown || noCardinality ? ', 그중 ' + [unknown ? '방향 미상 ' + unknown + '개' : '',
            noCardinality ? '카디널리티 모름 ' + noCardinality + '개' : ''].filter(Boolean).join(', ') : '') + ')' : '')
          // DB가 제공하는 시스템 카탈로그는 그리지 않는다(표준 산출물의 erd/와 같다). 이름은 진단 보기의 "DDL에 없는 테이블"에 있다.
          + (scope.systemCatalogs ? ' · 시스템 카탈로그 ' + scope.systemCatalogs + '개 제외' : '');
        if (scope.truncated) {
          // 테이블이 많으면 전체를 한 장에 그리지 않는다. 상한까지만 그리고, 영역이나 선택한 테이블로 나눠 보게 한다.
          warning.replaceChildren(
            '범위에 테이블이 ' + ui.formatCount(scope.total) + '개 있어 상한 ' + state.maxNodes + '개까지만 그렸다. 영역(테이블 이름 접두사 묶음)별로 보거나, 테이블을 골라 좁히거나, 상한을 올린다. ',
            h('button', { class: 'btn small', type: 'button', text: '영역으로 보기', on: { click: function () { setMode('area'); draw(); } } }));
        } else warning.replaceChildren();
        warning.style.display = scope.truncated ? '' : 'none';
        if (!scope.tables.length) {
          diagram.clear('테이블이 없다.');
          return;
        }
        var out = ERD.toMermaid(index, scope, { columns: state.columns, comments: state.comments });
        // 표준 산출물이 ERD를 나누는 한도(Mermaid의 기본 maxTextSize보다 조금 작은 45,000자)를 넘는 소스: 뷰어는 한도를 올려 두어 그리기는 하지만
        // 느리고 읽기 어렵다. 좁히는 방법을 알린다.
        if (!scope.truncated && out.source.length > ERD.DEFAULT_MAX_CHARS) {
          var parts = ['이 다이어그램의 Mermaid 소스는 ' + ui.formatCount(out.source.length) + '자다. 표준 산출물이 ERD를 영역으로 나누는 한도('
            + ui.formatCount(ERD.DEFAULT_MAX_CHARS) + '자)를 넘어 그리는 데 오래 걸릴 수 있다. 컬럼을 "키만"으로 줄이거나'
            + (state.mode === 'area' ? ' 테이블을 골라 좁힌다.' : ' 영역별로 본다. ')];
          if (state.mode !== 'area') {
            parts.push(h('button', { class: 'btn small', type: 'button', text: '영역으로 보기', on: { click: function () { setMode('area'); draw(); } } }));
          }
          warning.replaceChildren.apply(warning, parts);
          warning.style.display = '';
        }
        // 방향 미상(undirected)은 그린 뒤에 선 끝 표시를 지운다. 카디널리티 모름(cardinalityUnknown)은 지우지 않는다: 양쪽 다 가장 약한 표시로 남는다.
        diagram.render(out.source, out.aliases, { undirected: out.undirected, cardinalityUnknown: out.cardinalityUnknown });
      }
      var schedule = ui.debounce(draw, 200);

      var modeSeg = ui.createSegmented([['all', '전체'], ['area', '영역'], ['pick', '선택한 테이블']], state.mode, function (v) { state.mode = v; renderAreas(); schedule(); });
      modeSeg.el.classList.add('erd-mode');
      function setMode(v) {
        state.mode = v;
        modeSeg.set(v);
        renderAreas();
      }
      var chips = h('div', { class: 'chips' });
      function renderChips() {
        ui.renderChips(chips, index, state.tables, function (id) {
          state.tables = state.tables.filter(function (t) { return t !== id; });
          renderChips();
          schedule();
        });
      }
      function addTable(id) {
        if (state.tables.indexOf(id) < 0) state.tables.push(id);
        // 뷰를 고르면 뷰도 그리게 켠다.
        var picked = index.node(id);
        if (picked && picked.kind === 'DB_VIEW' && !state.includeViews) { state.includeViews = true; viewsCheck.input.checked = true; }
        setMode('pick');
        renderChips();
      }
      var picker = ui.createPicker({ index: index, kinds: ['DB_TABLE', 'DB_VIEW'], placeholder: '테이블 찾아 넣기 (이름, 주석)', onPick: function (n) { addTable(n.id); schedule(); } });
      var depthSeg = ui.createSegmented([[0, '0'], [1, '1'], [2, '2'], [3, '3']], state.depth, function (v) { state.depth = v; schedule(); });
      var inferredCheck = ui.createCheck('추론 관계 포함', true, function (on) { state.includeInferred = on; schedule(); }, 'SQL의 조인 조건에서 추론한 관계 (source=INFERRED)');
      var viewsCheck = ui.createCheck('뷰 포함', false, function (on) { state.includeViews = on; schedule(); }, '뷰(DB_VIEW)도 상자로 그린다. 이름 옆에 (VIEW)가 붙는다');
      viewsCheck.el.classList.add('include-views');
      var commentsCheck = ui.createCheck('주석', true, function (on) { state.comments = on; schedule(); }, '테이블과 컬럼의 주석(COMMENT ON)을 함께 적는다');
      var columnsSeg = ui.createSegmented([['all', '전체'], ['keys', '키만'], ['none', '숨김']], state.columns, function (v) { state.columns = v; schedule(); });
      columnsSeg.el.classList.add('erd-columns');
      var limitInput = h('input', { class: 'input num', type: 'number', value: String(state.maxNodes), attrs: { min: '1', max: String(MAX_LIMIT) } });
      limitInput.addEventListener('change', function () {
        var v = parseInt(limitInput.value, 10);
        state.maxNodes = v > 0 ? Math.min(v, MAX_LIMIT) : ERD.DEFAULT_MAX_NODES;
        limitInput.value = String(state.maxNodes);
        schedule();
      });

      var legend = h('div', { class: 'legend' }, ERD.LEGEND.map(function (item) {
        return h('div', { class: 'legend-item', title: item.description }, [
          legendSample(item),
          h('div', {}, [h('div', { text: item.label }), h('div', { class: 'd', text: item.description })])
        ]);
      }));

      var side = h('div', { class: 'pane side' }, [
        h('div', { class: 'pane-head' }, [h('span', { class: 'pane-title', text: '범위' })]),
        h('div', { class: 'pane-body' }, [
          h('div', { class: 'field' }, [h('div', { class: 'label', text: '테이블' }), modeSeg.el, areasBox, picker.el, chips,
            h('div', { class: 'row' }, [h('span', { class: 'muted', text: '이웃 단계' }), depthSeg.el])]),
          h('div', { class: 'field' }, [h('div', { class: 'label', text: '관계' }), inferredCheck.el, viewsCheck.el]),
          h('div', { class: 'field' }, [h('div', { class: 'label', text: '표시' }), commentsCheck.el,
            h('div', { class: 'row' }, [h('span', { class: 'muted', text: '컬럼' }), columnsSeg.el]),
            h('div', { class: 'row' }, [h('span', { class: 'muted', text: '테이블 수 상한' }), limitInput])]),
          h('div', { class: 'field' }, [h('div', { class: 'label', text: '범례' }), legend]),
          h('div', { class: 'hint', text: '관계는 부모(한쪽) ─ 자식(여러 쪽)으로 그린다. 추론 관계(점선)는 제약이 없어 부모가 반드시 있는지 알 수 없으므로 부모 쪽을 "0 또는 1"로 그린다. DB가 제공하는 시스템 카탈로그(데이터 사전) 테이블은 프로젝트 스키마가 아니라서 그리지 않는다. 방향을 모르는 추론 관계는 끝 표시 없이 선만 긋는다. 방향은 알지만(같은 테이블 쌍의 DDL 외래 키를 따름) 카디널리티를 모르는 추론 관계는 부모·자식 자리는 그대로 두고 양쪽 끝을 가장 약한 표시(0개 이상)로 그리며 라벨에 \'방향은 DDL, 카디널리티 모름\'을 적는다(N:M이라는 뜻이 아니다). 공통코드 테이블처럼 복합 키의 일부를 상수로 고정한 추론 관계는 라벨에 고정 컬럼을 적는다(예: ORDER_STATUS → CODE (GROUP_CD=\'ORDER_STATUS\', 추론 1건)). 테이블을 누르면 상세가 열리고, 추론의 근거 구문은 상세의 REFERENCES 엣지를 펼치면 나온다.' })
        ])
      ]);
      var main = h('div', { class: 'pane diagram' }, [
        h('div', { class: 'pane-head' }, [h('span', { class: 'pane-title', text: 'ERD' }), summary, h('span', { class: 'spacer' }), diagram.toolbar]),
        warning, diagram.banner, diagram.canvas, diagram.source
      ]);

      return {
        el: h('div', { class: 'view view-erd' }, [side, main]),
        show: function (params) {
          var p = params || {};
          if (p.table) {
            state.tables = [];
            addTable(p.table);
            draw();
          } else if (!drawn) {
            draw();
          } else {
            diagram.fit();
          }
        }
      };
    }
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);
