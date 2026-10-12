// 탐색 화면: 종류 필터, 이름 검색, 노드 상세(지나온 노드로 뒤로·앞으로). 결과 목록과 상세 사이의 나눔선.
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var G = CA.graph;
  var ui = CA.ui;
  var h = ui.h;
  CA.views = CA.views || {};

  /** 클래스 이름을 붙인다. 이름이 여럿이면(공백으로 나눈 것) 하나씩 넣는다: classList.add는 공백이 든 토큰을 거부한다. */
  function withClass(el, names) {
    String(names).split(/\s+/).forEach(function (name) {
      if (name) el.classList.add(name);
    });
    return el;
  }

  CA.views.explore = {
    title: '탐색',
    create: function (app) {
      var index = app.index;
      var selectedKinds = new Set();
      // 내용을 분석하지 않은 벤더·압축 파일(노드만 있다)은 기본으로 숨긴다. 있을 때만.
      var skippedCount = CA.search.countSkipped(index);
      var routeCount = CA.search.countClientRoutes(index);
      var hide = { undeclared: false, synthetic: false, skipped: skippedCount > 0, routes: routeCount > 0 };
      var results = [];
      var selectedId = null;

      // ---- 종류 필터
      var kindInputs = [];
      var kindRows = index.kindCounts.map(function (k) {
        var meta = G.kindMeta(k.kind);
        var input = h('input', { type: 'checkbox' });
        input.addEventListener('change', function () {
          if (input.checked) selectedKinds.add(k.kind); else selectedKinds.delete(k.kind);
          runSearch();
        });
        kindInputs.push(input);
        return h('label', { class: 'kind-row', title: k.kind }, [
          input, h('span', { class: 'swatch g-' + meta.group }), h('span', { class: 'kind-label', text: meta.label }), h('span', { class: 'n', text: ui.formatCount(k.count) })
        ]);
      });
      var kindsPane = h('div', { class: 'pane kinds' }, [
        h('div', { class: 'pane-head' }, [
          h('span', { class: 'pane-title', text: '종류' }), h('span', { class: 'spacer' }),
          h('button', { class: 'btn small', type: 'button', text: '모두 해제', title: '고른 종류가 없으면 모든 종류에서 찾는다', on: { click: function () {
            selectedKinds.clear();
            kindInputs.forEach(function (i) { i.checked = false; });
            runSearch();
          } } })
        ]),
        h('div', { class: 'pane-body' }, kindRows.length ? kindRows : [h('div', { class: 'empty', text: '노드가 없다' })]),
        h('div', { class: 'kind-options' }, [
          withClass(ui.createCheck('정의 없는 노드 숨기기', false, function (on) { hide.undeclared = on; runSearch(); },
            'declared=false: 소스가 없는 외부 라이브러리 타입·메서드, DDL에 없는 테이블처럼 참조만 된 노드').el, 'hide-undeclared'),
          withClass(ui.createCheck('합성 멤버 숨기기', false, function (on) { hide.synthetic = on; runSearch(); },
            'synthetic=true: 기본 생성자, 초기화 코드, record·enum의 자동 멤버, Lombok이 만들 멤버').el, 'hide-synthetic'),
          withClass(ui.createCheck('화면 내 이동(클라이언트 라우트) 숨기기 (' + ui.formatCount(routeCount) + ')', hide.routes, function (on) { hide.routes = on; runSearch(); },
            'clientRoute=true: 서버로 가는 요청이 아니라 화면 안의 이동(React Router 등)을 적은 요청 노드다. 엔드포인트 대상이 아니다').el, 'hide-client-routes' + (routeCount ? '' : ' none')),
          withClass(ui.createCheck('분석 안 한 벤더·압축 파일 숨기기 (' + ui.formatCount(skippedCount) + ')', hide.skipped, function (on) { hide.skipped = on; runSearch(); },
            'skipped=vendor|minified: 벤더(서드파티) 스크립트, 벤더 폴더 안의 페이지, 압축된 스크립트라 내용을 분석하지 않은 모듈·페이지. 노드만 있고 함수와 요청은 없다. 어느 폴더를 건너뛰었는지는 진단 FILE_SKIPPED에 있다').el, 'hide-skipped')
        ])
      ]);

      // ---- 결과 목록
      var searchInput = h('input', { class: 'input grow search', type: 'search', placeholder: '이름, 전체 이름, 주석 (공백으로 여러 낱말)', attrs: { autocomplete: 'off', spellcheck: 'false' } });
      var countEl = h('span', { class: 'muted result-count' });
      var list = ui.createVirtualList({
        render: function (node) {
          var row = ui.nodeRow(node, node.id === selectedId ? 'selected' : '');
          row.addEventListener('click', function () { select(node.id, 'list'); });
          return row;
        }
      });
      function runSearch() {
        results = CA.search.search(index, { text: searchInput.value, kinds: Array.from(selectedKinds), hideUndeclared: hide.undeclared, hideSynthetic: hide.synthetic, hideSkipped: hide.skipped, hideClientRoutes: hide.routes });
        countEl.textContent = ui.formatCount(results.length) + '개';
        list.setItems(results);
      }
      searchInput.addEventListener('input', ui.debounce(runSearch, 150));
      list.el.addEventListener('keydown', function (e) {
        if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
        var at = results.findIndex(function (n) { return n.id === selectedId; });
        var next = Math.max(0, Math.min(results.length - 1, at + (e.key === 'ArrowDown' ? 1 : -1)));
        if (results[next]) { select(results[next].id, 'list'); list.reveal(next); }
        e.preventDefault();
      });
      var resultsPane = h('div', { class: 'pane results' }, [
        h('div', { class: 'pane-head' }, [searchInput, countEl]),
        list.el
      ]);

      // ---- 상세
      var detailBody = h('div', { class: 'pane-body' }, [h('div', { class: 'empty', text: '목록에서 노드를 고르면 속성과 관계가 여기에 나온다.' })]);
      var nav = ui.createTrailNav(index, function (id) { select(id, 'trail'); });
      var detailPane = h('div', { class: 'pane detail' }, [h('div', { class: 'pane-head detail-nav' }, [nav.back, nav.forward]), detailBody]);
      // 결과 목록과 상세 사이의 나눔선: 끌어서 두 칸의 너비를 바꾼다(F-016-R51). 최소 너비는 viewer.css의 .results, .detail과 같다.
      var splitter = ui.createSplitter(resultsPane, detailPane, { minBefore: 280, minAfter: 320 });

      /**
       * 노드를 고른다. how는 어떻게 왔는지다: 'list'(결과 목록), 'follow'(상세 안의 링크), 'trail'(뒤로·앞으로 단추), 'address'(주소의 #).
       * 주소는 자취와 같이 간다: 자취에 칸이 늘면 주소에도 새 항목을 쌓아 브라우저의 뒤로 가기로 돌아올 수 있게 하고,
       * 목록에서 연달아 고른 것(자취의 칸을 바꿈)은 주소도 그 자리에서 바꾼다. 주소에서 온 이동은 주소를 건드리지 않는다.
       */
      function select(id, how) {
        var first = selectedId == null;
        var step = null;   // 주소에 할 일: 'push'(새 항목), 'replace'(그 자리에서 바꿈), 그 밖에는 그대로 둔다
        if (how === 'address') nav.trail.sync(id);
        else if (how === 'trail') step = 'push';
        else step = nav.trail.visit(id, how === 'list');
        selectedId = id;
        ui.renderDetail(detailBody, app, id, { follow: function (to) { select(to, 'follow'); } });
        nav.update();
        list.refresh();
        if (step === 'replace' || (step === 'push' && first)) app.setHash('explore', { id: id });
        else if (step === 'push') app.pushHash('explore', { id: id });
      }

      runSearch();
      return {
        el: h('div', { class: 'view view-explore' }, [kindsPane, resultsPane, splitter.el, detailPane]),
        show: function (params) {
          list.refresh();
          if (params && params.id && params.id !== selectedId) {
            select(params.id, 'address');
            var at = results.findIndex(function (n) { return n.id === params.id; });
            if (at >= 0) list.reveal(at);
          }
        },
        /** 탐색 화면이 떠 있을 때 다른 노드로 옮겨 간다. */
        showDetail: function (id) { select(id, 'follow'); }
      };
    }
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);
