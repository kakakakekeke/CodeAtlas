// 클래스 다이어그램 화면: 패키지나 타입 범위를 골라 그린다.
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var CD = CA.classDiagram;
  var ui = CA.ui;
  var h = ui.h;
  var MAX_LIMIT = 300;
  var MAX_PACKAGE_ROWS = 300;
  var RELATION_LABELS = { EXTENDS: '상속', IMPLEMENTS: '구현', ASSOCIATES: '연관 (필드)', DEPENDS_ON: '의존 (사용)' };
  CA.views = CA.views || {};

  CA.views.classdiagram = {
    title: '클래스 다이어그램',
    create: function (app) {
      var index = app.index;
      var state = {
        packages: new Set(), includeSubpackages: false, types: [], depth: 1,
        kinds: new Set(CD.RELATION_KINDS), showMembers: true, direction: 'TB', maxNodes: CD.DEFAULT_MAX_NODES,
        includeSupertypes: true, includeExternal: true, includeAnonymous: false, showSynthetic: false, externalPackages: false
      };
      var allPackages = CD.packages(index);

      var diagram = ui.createDiagram({ fileName: 'class-diagram', onNodeClick: function (id) { app.showDetail(id); } });
      var summary = h('span', { class: 'muted dg-summary' });
      var warning = h('div', { class: 'banner', attrs: { style: 'display:none' } });

      function draw() {
        var hasScope = state.packages.size > 0 || state.types.length > 0;
        if (!hasScope) {
          summary.textContent = '';
          warning.style.display = 'none';
          diagram.clear('왼쪽에서 패키지나 타입을 고르면 그 범위의 타입과 관계를 그린다.');
          return;
        }
        var scope = CD.selectScope(index, {
          packages: Array.from(state.packages), includeSubpackages: state.includeSubpackages,
          types: state.types, depth: state.depth, kinds: Array.from(state.kinds), maxNodes: state.maxNodes,
          includeSupertypes: state.includeSupertypes, includeExternal: state.includeExternal, includeAnonymous: state.includeAnonymous
        });
        summary.textContent = '타입 ' + ui.formatCount(scope.types.length) + '개 · 관계 ' + ui.formatCount(scope.relations.length) + '개';
        warning.textContent = scope.truncated
          ? '범위에 타입이 ' + ui.formatCount(scope.total) + '개 있어 상한 ' + state.maxNodes + '개까지만 그렸다. 범위를 좁히거나 상한을 올린다.' : '';
        warning.style.display = scope.truncated ? '' : 'none';
        if (!scope.types.length) {
          diagram.clear('고른 범위에 타입이 없다.');
          return;
        }
        var out = CD.toMermaid(index, scope, { showMembers: state.showMembers, showSynthetic: state.showSynthetic, direction: state.direction });
        diagram.render(out.source, out.aliases);
      }
      var schedule = ui.debounce(draw, 200);

      // ---- 패키지
      var packageFilter = h('input', { class: 'input', type: 'search', placeholder: '패키지 이름으로 거르기', attrs: { autocomplete: 'off', spellcheck: 'false' } });
      var packageList = h('div', { class: 'pkg-list' });
      function renderPackages() {
        var text = packageFilter.value.trim().toLowerCase();
        // 소스가 없는 패키지(외부 라이브러리)는 고를 때만 목록에 넣는다. 이미 고른 패키지는 늘 보인다.
        var matched = allPackages.filter(function (p) {
          if (state.packages.has(p.name)) return true;
          if (p.external && !state.externalPackages) return false;
          return !text || p.name.toLowerCase().indexOf(text) >= 0;
        });
        var rows = matched.slice(0, MAX_PACKAGE_ROWS).map(function (p) {
          var input = h('input', { type: 'checkbox', checked: state.packages.has(p.name) });
          input.addEventListener('change', function () {
            if (input.checked) state.packages.add(p.name); else state.packages.delete(p.name);
            schedule();
          });
          return h('label', { class: 'pkg-row' + (p.external ? ' external' : ''), title: p.name }, [input, h('span', { class: 'pkg-name', text: p.name || '(기본 패키지)' }),
            p.external ? h('span', { class: 'tag', text: '외부', title: '이 패키지의 타입은 모두 소스가 없다' }) : null, h('span', { class: 'n', text: String(p.count) })]);
        });
        if (!matched.length) rows.push(h('div', { class: 'hint', text: allPackages.length ? '맞는 패키지가 없다' : 'Java 타입이 없다' }));
        if (matched.length > MAX_PACKAGE_ROWS) rows.push(h('div', { class: 'hint', text: '외 ' + (matched.length - MAX_PACKAGE_ROWS) + '개. 이름으로 좁힌다.' }));
        packageList.replaceChildren.apply(packageList, rows);
      }
      packageFilter.addEventListener('input', ui.debounce(renderPackages, 120));
      var subCheck = ui.createCheck('하위 패키지 포함', false, function (on) { state.includeSubpackages = on; schedule(); });
      var externalPackagesCheck = ui.createCheck('외부 라이브러리 패키지도 보기', false, function (on) { state.externalPackages = on; renderPackages(); },
        '소스가 없는 타입만 있는 패키지(' + allPackages.filter(function (p) { return p.external; }).length + '개)를 목록에 넣는다');
      externalPackagesCheck.el.classList.add('show-external-packages');

      // ---- 타입
      var chips = h('div', { class: 'chips' });
      function renderTypeChips() {
        ui.renderChips(chips, index, state.types, function (id) {
          state.types = state.types.filter(function (t) { return t !== id; });
          renderTypeChips();
          schedule();
        });
      }
      function addType(id) {
        if (state.types.indexOf(id) < 0) state.types.push(id);
        renderTypeChips();
      }
      var typePicker = ui.createPicker({ index: index, kinds: ['JAVA_TYPE'], placeholder: '타입 찾아 넣기', onPick: function (n) { addType(n.id); schedule(); } });
      var depthSeg = ui.createSegmented([[0, '0'], [1, '1'], [2, '2'], [3, '3']], state.depth, function (v) { state.depth = v; schedule(); });

      // ---- 관계와 표시
      var relationChecks = CD.RELATION_KINDS.map(function (k) {
        return ui.createCheck(RELATION_LABELS[k], true, function (on) {
          if (on) state.kinds.add(k); else state.kinds.delete(k);
          schedule();
        }, k).el;
      });
      var membersCheck = ui.createCheck('필드와 메서드', true, function (on) { state.showMembers = on; schedule(); });
      function namedCheck(className, label, key, title) {
        var c = ui.createCheck(label, state[key], function (on) { state[key] = on; schedule(); }, title);
        c.el.classList.add(className);
        return c.el;
      }
      var syntheticCheck = namedCheck('show-synthetic', '합성 멤버', 'showSynthetic',
        'synthetic=true: 소스에 없는 멤버(기본 생성자, 초기화 코드, record·enum의 자동 멤버, Lombok이 만들 getter·setter 등)');
      var supertypesCheck = namedCheck('include-supertypes', '범위 밖 상위 타입', 'includeSupertypes',
        '범위 안 타입이 상속·구현하는 타입은 다른 패키지에 있어도 함께 그린다');
      var externalCheck = namedCheck('include-external', '외부 타입 (소스 없음)', 'includeExternal',
        'external=true 또는 declared=false: 소스가 없는 라이브러리 타입. 점선 테두리로 그린다');
      var anonymousCheck = namedCheck('include-anonymous', '익명·로컬 타입', 'includeAnonymous', 'anonymous=true, local=true');
      var directionSeg = ui.createSegmented([['TB', '위→아래'], ['LR', '왼→오른']], state.direction, function (v) { state.direction = v; schedule(); });
      var limitInput = h('input', { class: 'input num', type: 'number', value: String(state.maxNodes), attrs: { min: '1', max: String(MAX_LIMIT) } });
      limitInput.addEventListener('change', function () {
        var v = parseInt(limitInput.value, 10);
        state.maxNodes = v > 0 ? Math.min(v, MAX_LIMIT) : CD.DEFAULT_MAX_NODES;
        limitInput.value = String(state.maxNodes);
        schedule();
      });

      var side = h('div', { class: 'pane side' }, [
        h('div', { class: 'pane-head' }, [h('span', { class: 'pane-title', text: '범위' }), h('span', { class: 'spacer' }),
          h('button', { class: 'btn small', type: 'button', text: '비우기', on: { click: function () {
            state.packages.clear(); state.types = []; renderPackages(); renderTypeChips(); schedule();
          } } })]),
        h('div', { class: 'pane-body' }, [
          h('div', { class: 'field' }, [h('div', { class: 'label', text: '패키지' }), packageFilter, packageList, subCheck.el, externalPackagesCheck.el]),
          h('div', { class: 'field' }, [h('div', { class: 'label', text: '타입' }), typePicker.el, chips,
            h('div', { class: 'row' }, [h('span', { class: 'muted', text: '이웃 단계' }), depthSeg.el])]),
          h('div', { class: 'field' }, [h('div', { class: 'label', text: '관계' })].concat(relationChecks)),
          h('div', { class: 'field' }, [h('div', { class: 'label', text: '넣을 타입' }), supertypesCheck, externalCheck, anonymousCheck]),
          h('div', { class: 'field' }, [h('div', { class: 'label', text: '표시' }), membersCheck.el, syntheticCheck,
            h('div', { class: 'row' }, [h('span', { class: 'muted', text: '방향' }), directionSeg.el]),
            h('div', { class: 'row' }, [h('span', { class: 'muted', text: '타입 수 상한' }), limitInput])]),
          h('div', { class: 'hint', text: '점선 테두리는 소스가 없는 타입(외부 라이브러리)이다. 도형을 누르면 상세가 열린다.' })
        ])
      ]);
      var main = h('div', { class: 'pane diagram' }, [
        h('div', { class: 'pane-head' }, [h('span', { class: 'pane-title', text: '클래스 다이어그램' }), summary, h('span', { class: 'spacer' }), diagram.toolbar]),
        warning, diagram.banner, diagram.canvas, diagram.source
      ]);

      renderPackages();
      draw();
      return {
        el: h('div', { class: 'view view-classdiagram' }, [side, main]),
        show: function (params) {
          var p = params || {};
          if (p.type) {
            state.packages.clear();
            state.types = [];
            addType(p.type);
            renderPackages();
            draw();
          } else if (p.package != null) {
            state.types = [];
            state.packages = new Set([p.package]);
            renderTypeChips();
            renderPackages();
            draw();
          } else {
            diagram.fit();
          }
        }
      };
    }
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);
