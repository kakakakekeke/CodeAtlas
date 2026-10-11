// 콜트리 화면: 기준 노드의 호출하는 쪽과 호출되는 쪽 트리. 자식은 펼칠 때 계산한다.
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var G = CA.graph;
  var CT = CA.calltree;
  var ui = CA.ui;
  var h = ui.h;
  var MAX_EXPAND_NODES = 3000;
  CA.views = CA.views || {};

  /** 한 방향의 트리 영역. */
  function createTreePane(app, direction, title, hint) {
    var index = app.index;
    var rootNode = null;
    var notice = h('div', { class: 'banner', attrs: { style: 'display:none' } });
    var list = ui.createVirtualList({ render: renderRow });
    var copyButton = h('button', { class: 'btn small', type: 'button', text: '글로 복사', on: { click: function () {
      if (rootNode) ui.copyText(CT.toText(index, rootNode)).then(function () { ui.flash(copyButton, '복사함'); });
    } } });

    function flatten() {
      var rows = [];
      (function walk(t) {
        rows.push(t);
        if (t.open && t.children) t.children.forEach(walk);
      })(rootNode);
      return rows;
    }
    function redraw(keepScroll) {
      list.setItems(rootNode ? flatten() : [], keepScroll);
    }
    function toggle(t) {
      if (t.open) t.open = false;
      else { CT.expand(index, t); t.open = true; }
      redraw(true);
    }
    function renderRow(t) {
      var expandable = CT.hasChildren(index, t);
      var twist = h('button', {
        class: 'twist' + (expandable ? '' : ' leaf'), type: 'button', text: t.open ? '▾' : '▸',
        attrs: { 'aria-label': t.open ? '접기' : '펼치기' }, on: { click: function (e) { e.stopPropagation(); if (expandable) toggle(t); } }
      });
      var cells = [twist];
      // 그래프의 노드가 아닌 줄: 오버로드 후보를 묶은 줄, 레이아웃을 쓰는 화면 수를 접은 줄(표준 산출물의 [ambiguous n], [layout] …를 쓰는 화면 n개)
      if (t.synthetic) {
        var ambiguous = t.synthetic.type === 'ambiguous';
        cells.push(h('span', { class: 'rel', text: t.link.label }));
        cells.push(h('span', { class: 'tag ' + (ambiguous ? 'ambiguous' : 'layout'), text: ambiguous ? '후보 ' + t.synthetic.count + '개' : '레이아웃',
          title: ambiguous ? 'ambiguous=true: 오버로드를 가리지 못해 이 호출 지점에서 같은 이름의 메서드 ' + t.synthetic.count + '개로 이었다. 아래 줄이 후보다(표준 산출물의 [ambiguous n])'
            : '이 JSP를 레이아웃(데코레이터, tiles template)으로 쓰는 화면이다. 줄줄이 내지 않고 수만 적는다("레이아웃 펼치기"를 켜면 화면마다 나온다)' }));
        cells.push(h('span', { class: 'name', text: t.synthetic.text }));
        if (ambiguous) cells.push(ui.confBadge('INFERRED'));
        var srow = h('div', { class: 'vrow trow synthetic' }, cells);
        srow.style.paddingLeft = (6 + t.depth * 16) + 'px';
        srow.addEventListener('dblclick', function () { if (expandable) toggle(t); });
        return srow;
      }
      if (t.link) cells.push(h('span', { class: 'rel', text: t.link.label, title: t.link.kind + (t.link.reversed ? ' (화살표 반대 방향)' : '') }));
      cells.push(t.node ? ui.kindBadge(t.node.kind) : h('span', { class: 'tag warn', text: '노드 없음' }));
      var undeclared = t.node && t.node.attrs.declared === false;
      cells.push(h('span', { class: 'name' + (undeclared ? ' undeclared' : ''), text: CT.labelOf(t), title: index.label(t.id) + (undeclared ? ' (정의 없음)' : ''),
        on: { click: function () { app.showDetail(t.id); } } }));
      if (t.link && t.link.viaInterface) {
        cells.push(h('span', { class: 'tag via-interface', text: '인터페이스 경유',
          title: '이 줄은 ' + G.shortLabel(index.node(t.link.viaMethod), t.link.viaMethod) + '을(를) 부른다. 그 메서드를 재정의·구현한 아래 메서드로 디스패치될 수 있다(표준 산출물의 [via interface])' }));
      }
      if (t.link && t.link.receiverUncertain) {
        cells.push(h('span', { class: 'tag receiver-uncertain', text: '수신자?',
          title: '수신자 타입에 맞는 대상을 고르지 못해 전부 따라간 줄이다: 상위 타입에서 물려받은 매퍼 메서드가 하위 매퍼마다 실행하는 구문, 또는 리플렉션 호출의 대상 가운데 어느 것이 실행되는지는 이 경로에서 정해지지 않는다(표준 산출물의 [?])' }));
      }
      if (t.link && t.link.kind === 'CALLS' && t.link.detail === 'reflection') {
        cells.push(h('span', { class: 'tag reflect', text: '리플렉션', title: 'callType=reflection: Method.invoke의 대상을 추론한 호출이다(표준 산출물의 [reflect])' }));
      }
      if (t.link && t.link.kind === 'CALLS' && t.link.detail === 'modelAttribute') {
        cells.push(h('span', { class: 'tag before', text: '요청 전',
          title: 'callType=modelAttribute: 같은 컨트롤러의 @ModelAttribute 메서드다. Spring이 요청마다 핸들러보다 먼저 부른다(표준 산출물의 [before]). 줄의 위치는 호출 지점이 아니라 그 메서드의 선언이다' }));
      }
      if (t.link && (CT.isRedirectLink(t.link) || CT.isForwardLink(t.link))) {
        cells.push(h('span', { class: 'tag screen-move', text: CT.isRedirectLink(t.link) ? '리다이렉트' : '포워드',
          title: 'callType=' + t.link.detail + ': 다른 엔드포인트의 핸들러로 넘어간다(표준 산출물의 [' + t.link.detail + ']). 화면 이동이라 "모두 펼치기"는 여기서 멈춘다("화면 이동 펼치기"로 켠다)' }));
      }
      if (t.link && CT.isLayoutLink(t.link)) {
        cells.push(h('span', { class: 'tag layout', text: '레이아웃',
          title: '레이아웃 관계다(sitemesh 데코레이터, tiles template과 그 조각. 표준 산출물의 [layout]). 모든 화면이 공통 헤더·메뉴의 요청을 끌고 오지 않게 "모두 펼치기"는 여기서 멈춘다("레이아웃 펼치기"로 켠다)' }));
      }
      if (t.node && t.node.kind === 'HTTP_REQUEST' && t.node.attrs.clientRoute === true) {
        cells.push(h('span', { class: 'tag route', text: '화면 내 이동', title: 'clientRoute=true: 서버 요청이 아니라 화면 안의 이동(클라이언트 라우트)이다. 엔드포인트로 이어지지 않는다(표준 산출물의 [route])' }));
      }
      if (t.link && t.link.detail === '로드 미확인') {
        cells.push(h('span', { class: 'tag warn', text: '로드 미확인', title: 'loadUnverified=true: 프로젝트에 정의가 하나뿐이라 이었지만 그 스크립트를 이 화면이 함께 싣는지는 확인하지 못했다' }));
      }
      if (t.shared > 0) {
        cells.push(h('span', { class: 'tag layout shared', text: '공통 화면 · 포함하는 화면 ' + t.shared + '개',
          title: '여러 화면에 들어가는 공통 JSP(헤더·메뉴·레이아웃: 5개 이상의 페이지가 include하거나 레이아웃이다). 그것을 포함하는 화면 전부로 올라가지 않는다(표준 산출물의 [shared]). "레이아웃 펼치기"를 켜면 화면마다 올라간다' }));
      }
      if (G.borrowedDataLoadNote(t.node)) {
        cells.push(h('span', { class: 'tag warn', text: G.borrowedDataLoadNote(t.node), title: 'dataLoad=true, borrowedFrom: 고른 방언의 파일에 이 테이블의 적재가 없어 다른 방언 파일의 묶음을 남겼다' }));
      } else if (t.node && t.node.kind === 'SQL_STATEMENT' && t.node.attrs.borrowedFrom) {
        cells.push(h('span', { class: 'tag warn', text: '다른 방언 ' + t.node.attrs.borrowedFrom + '에서 보충', title: 'borrowedFrom: 선택한 방언의 매퍼 파일에 없어 다른 방언 파일의 정의를 쓴 구문이다' }));
      }
      if (t.link && t.link.triggerUncertain) {
        cells.push(h('span', { class: 'tag trigger-uncertain', text: '트리거?',
          title: '이 쓰기에 트리거가 실제로 일어나는지 알 수 없다: 트리거의 사건(triggerEvent)을 모르거나, 컬럼 지정 트리거(UPDATE OF)인데 구문이 쓰는 컬럼을 모르거나, 쓰기의 종류를 모른다(표준 산출물의 [trigger?])' }));
      }
      if (t.node && t.node.kind === 'SQL_STATEMENT' && t.node.attrs.sequence === true) {
        cells.push(h('span', { class: 'tag', text: '시퀀스 참조 식', title: 'sequence=true: SQL 구문이 아니라 루틴 본문의 식 안에서 시퀀스를 참조한 자리다' }));
      }
      if (t.link && t.link.count > 1) cells.push(h('span', { class: 'tag', text: '×' + t.link.count, title: '같은 대상을 가리키는 엣지 수' }));
      if (t.link && t.link.confidence !== 'RESOLVED') cells.push(ui.confBadge(t.link.confidence));
      if (t.link && t.link.hidden > 0) cells.push(h('span', { class: 'tag', text: '다른 구현 ' + t.link.hidden + '개 숨김', title: '호출하는 타입의 주입 정보(INJECTS)로 구현을 좁혔다. "모든 구현 보기"를 켜면 다 나온다.' }));
      if (t.cycle) cells.push(h('span', { class: 'tag warn', text: '순환', title: '이 노드는 위쪽 경로에 이미 있다. 여기서 멈춘다.' }));
      // 이 노드 아래에서 옵션 때문에 접힌 줄(접근자, 소스 없는 메서드, 데이터 적재 구문)의 수
      var hidden = t.hidden;
      var hiddenTotal = hidden ? hidden.accessors + hidden.external + hidden.dataLoad + (hidden.routes || 0) + (hidden.builders || 0) : 0;
      if (hiddenTotal > 0) {
        var parts = [];
        if (hidden.accessors) parts.push('접근자 ' + hidden.accessors);
        if (hidden.builders) parts.push('빌더 호출 ' + hidden.builders);
        if (hidden.external) parts.push('소스 없는 메서드 ' + hidden.external);
        if (hidden.dataLoad) parts.push('데이터 적재 구문 ' + hidden.dataLoad);
        if (hidden.routes) parts.push('화면 내 이동 ' + hidden.routes);
        cells.push(h('span', { class: 'tag folded', text: '접힘 ' + hiddenTotal,
          title: '이 노드 아래에서 접은 줄: ' + parts.join(' · ') + '. 위의 "접근자 보기", "외부 메서드 보기", "데이터 적재 포함", "화면 내 이동 숨기기"로 편다' }));
      }
      var where = t.link ? CT.whereOf(t) : null;
      if (where) cells.push(h('span', { class: 'where', text: where, title: t.link.locations.length ? t.link.locations.join('\n') : '정의 위치' }));
      var row = h('div', { class: 'vrow trow' + (t.cycle ? ' cycle' : '') }, cells);
      row.style.paddingLeft = (6 + t.depth * 16) + 'px';
      row.addEventListener('dblclick', function () { if (expandable) toggle(t); });
      return row;
    }

    return {
      el: h('div', { class: 'pane tree-' + direction }, [
        h('div', { class: 'pane-head' }, [h('span', { class: 'pane-title', text: title }), h('span', { class: 'muted', text: hint }), h('span', { class: 'spacer' }), copyButton]),
        notice,
        list.el
      ]),
      setRoot: function (id, opts) {
        notice.style.display = 'none';
        rootNode = id ? CT.createRoot(index, id, direction, opts) : null;
        if (rootNode) { CT.expand(index, rootNode); rootNode.open = true; }
        redraw(false);
      },
      expandAll: function (maxDepth, expandScreens, expandLayouts) {
        if (!rootNode) return;
        var result = CT.expandAll(index, rootNode, { maxDepth: maxDepth, maxNodes: MAX_EXPAND_NODES, expandScreens: expandScreens, expandLayouts: expandLayouts });
        (function open(t) {
          if (t.children && t.children.length) { t.open = true; t.children.forEach(open); }
        })(rootNode);
        var notes = [];
        if (result.truncated) notes.push('깊이 ' + maxDepth + ' 또는 노드 ' + ui.formatCount(MAX_EXPAND_NODES) + '개에서 멈췄다.');
        if (result.screens) {
          notes.push((direction === 'callers' ? '화면을 그리는 컨트롤러 ' : '컨트롤러가 그리는 화면 ') + result.screens
            + '곳에서 멈췄다(그 너머는 다른 화면으로의 이동이다). "화면 이동 펼치기"를 켜면 끝까지 펼친다.');
        }
        if (notes.length) notes.push('▸ 표시가 있는 줄은 더 펼칠 수 있다.');
        notice.textContent = notes.join(' ');
        notice.style.display = notes.length ? '' : 'none';
        redraw(true);
      },
      collapse: function () {
        if (!rootNode) return;
        (function close(t) { if (t.children) t.children.forEach(close); t.open = false; })(rootNode);
        rootNode.open = true;
        notice.style.display = 'none';
        redraw(false);
      },
      refresh: function () { list.refresh(); }
    };
  }

  /**
   * 시작 노드 묶음: 표준 산출물의 콜트리 파일(endpoints.md, batch-jobs.md, filters.md)이 시작하는 노드들이다.
   * 단추를 누르면 목록이 열리고, 고르면 그 노드가 기준이 된다.
   */
  function createEntryGroups(app, onPick) {
    var index = app.index;
    var groups = [
      { key: 'endpoints', label: '엔드포인트', file: 'calltree/endpoints.md', note: '엔드포인트마다 요청을 받는 핸들러에서 테이블까지 내려간다',
        items: function () {
          return index.nodesOfKind('HTTP_ENDPOINT').slice().sort(function (a, b) { return G.compareText(a.id, b.id); })
            .map(function (n) { return { id: n.id, node: n, label: G.endpointLabel(n, n.id), tag: null }; });
        } },
      { key: 'jobs', label: '배치 잡', file: 'calltree/batch-jobs.md', note: '잡의 스텝을 실행 흐름(NEXT) 순서로, 스텝의 구성 요소에서 테이블까지 내려간다',
        items: function () {
          return index.nodesOfKind('BATCH_JOB').slice().sort(function (a, b) { return G.compareText(a.id, b.id); })
            .map(function (n) { return { id: n.id, node: n, label: G.shortLabel(n, n.id), tag: null }; });
        } },
      { key: 'filters', label: '필터·인터셉터', file: 'calltree/filters.md',
        note: '요청마다 컨테이너가 부르는 진입 메서드(doFilter, doFilterInternal, preHandle, postHandle, afterCompletion, afterConcurrentHandlingStarted)에서 시작한다. 엔드포인트의 트리에는 나오지 않는 요청 전·후 처리(로그인 검사의 redirect 등)가 보인다',
        items: function () {
          return CT.filterEntries(index).map(function (e) { return { id: e.id, node: e.node, label: e.label, tag: e.stereotype === 'filter' ? '필터' : '인터셉터' }; });
        } }
    ];
    var counts = {
      endpoints: index.nodesOfKind('HTTP_ENDPOINT').length,
      jobs: index.nodesOfKind('BATCH_JOB').length,
      filters: CT.filterEntries(index).length
    };
    var current = null;
    var all = [];
    var title = h('span', { class: 'pane-title' });
    var note = h('div', { class: 'hint entry-note' });
    var filter = h('input', { class: 'input', type: 'search', placeholder: '이름으로 거르기', attrs: { autocomplete: 'off', spellcheck: 'false' } });
    var list = ui.createVirtualList({ render: function (item) {
      var row = h('div', { class: 'vrow entry-row', title: item.id }, [
        item.node ? ui.kindBadge(item.node.kind) : null,
        item.tag ? h('span', { class: 'tag', text: item.tag }) : null,
        h('span', { class: 'name', text: item.label }),
        h('span', { class: 'qn', text: item.node ? G.lineLocation(item.node.location) || '' : '' })
      ]);
      row.addEventListener('click', function () { close(); onPick(item.id); });
      return row;
    } });
    var panel = h('div', { class: 'entry-panel' }, [
      h('div', { class: 'pane-head' }, [title, filter, h('span', { class: 'spacer' }),
        h('button', { class: 'btn small', type: 'button', text: '닫기', on: { click: function () { close(); } } })]),
      note, list.el
    ]);
    var buttons = groups.filter(function (g) { return counts[g.key] > 0; }).map(function (g) {
      return h('button', { class: 'btn small', type: 'button', title: '표준 산출물 ' + g.file + '이 시작하는 노드 목록에서 기준을 고른다',
        attrs: { 'data-group': g.key, 'aria-expanded': 'false' }, on: { click: function () { if (current === g) close(); else show(g); } } },
      [g.label, h('span', { class: 'n', text: ' ' + ui.formatCount(counts[g.key]) })]);
    });
    function applyFilter() {
      var tokens = String(filter.value || '').trim().toLowerCase().split(/\s+/).filter(Boolean);
      list.setItems(tokens.length ? all.filter(function (item) {
        var hay = (item.label + ' ' + item.id).toLowerCase();
        return tokens.every(function (t) { return hay.indexOf(t) >= 0; });
      }) : all);
    }
    function show(g) {
      current = g;
      all = g.items();
      title.textContent = g.label + ' ' + ui.formatCount(all.length) + '개';
      note.textContent = g.note + '. 표준 산출물의 ' + g.file + ' 파일이 시작하는 노드와 같은 묶음이다.';
      filter.value = '';
      panel.classList.add('open');
      buttons.forEach(function (b) { b.setAttribute('aria-expanded', String(b.getAttribute('data-group') === g.key)); });
      applyFilter();
    }
    function close() {
      current = null;
      panel.classList.remove('open');
      buttons.forEach(function (b) { b.setAttribute('aria-expanded', 'false'); });
    }
    filter.addEventListener('input', ui.debounce(applyFilter, 120));
    return { el: h('div', { class: 'entry-groups' }, buttons), panel: panel, close: close };
  }

  CA.views.calltree = {
    title: '콜트리',
    create: function (app) {
      var index = app.index;
      var rootId = null;
      var includeColumns = false;
      var allImplementations = false;
      var expandScreens = false;
      var expandLayouts = false;
      // 화면 내 이동(클라이언트 라우트)은 그런 요청이 있는 그래프에서 기본으로 접는다(탐색과 같다). React 컴포넌트의 트리에서는
      // [route] 줄이 대부분을 차지한다(실제 프로젝트의 헤더 컴포넌트: 81줄 가운데 59줄).
      var routeCount = CA.search.countClientRoutes(index);
      var hideRoutes = routeCount > 0;
      // 접기의 기본값은 표준 산출물(export.calltree.hideAccessors·hideExternal=true, export.includeDataLoad=false)과 같다.
      var showAccessors = false;
      var showExternal = false;
      var includeDataLoad = false;

      var rootBox = h('div', { class: 'tree-root' }, [h('span', { class: 'muted', text: '기준 노드를 찾거나 묶음에서 고른다 (엔드포인트, 메서드, JSP, JS 함수, SQL 구문, 테이블, 루틴, 배치 잡, 필터·인터셉터 등)' })]);
      var picker = ui.createPicker({
        index: index, kinds: CT.ROOT_KINDS, placeholder: '기준 노드 찾기',
        onPick: function (node) { app.navigate('calltree', { id: node.id }); }
      });
      var depthInput = h('input', { class: 'input num', type: 'number', value: '12', title: '모두 펼칠 때의 최대 깊이', attrs: { min: '1', max: '50' } });
      var columnsCheck = ui.createCheck('컬럼 포함', false, function (on) { includeColumns = on; setRoot(rootId); }, 'SQL 구문 아래에 읽고 쓰는 컬럼도 보인다');

      var implementationsCheck = ui.createCheck('모든 구현 보기', false, function (on) { allImplementations = on; setRoot(rootId); },
        '끄면 인터페이스·추상 메서드의 구현을 호출하는 타입의 주입 정보(INJECTS의 candidates, 추론된 주입)로 좁힌다. 위로 올라갈 때도 다른 구현을 주입받는 호출자를 뺀다. 켜면 직접·간접 구현과 호출자를 모두 보인다');
      implementationsCheck.el.classList.add('all-implementations');
      var accessorsCheck = ui.createCheck('접근자 보기', false, function (on) { showAccessors = on; setRoot(rootId); },
        '끄면(기본) 접근자 호출 줄을 접는다: Lombok이 만들 멤버, 이름이 getXxx/setXxx/isXxx이면서 그 메서드(와 재정의한 메서드)에서 다른 호출·구문 실행이 없는 메서드, 부수 효과 없는 생성자(다른 메서드를 부르지 않는 <init>), MyBatis Generator의 *Example·*Example$Criteria 빌더 호출. 표준 산출물의 export.calltree.hideAccessors와 같다');
      accessorsCheck.el.classList.add('show-accessors');
      var externalCheck = ui.createCheck('외부 메서드 보기', false, function (on) { showExternal = on; setRoot(rootId); },
        '끄면(기본) 소스 없는 메서드(라이브러리, JDK, 정의를 찾지 못한 메서드)와 프로젝트 밖으로 가는 요청의 줄을 접는다. 표준 산출물의 export.calltree.hideExternal과 같다');
      externalCheck.el.classList.add('show-external');
      var dataLoadCheck = ui.createCheck('데이터 적재 포함', false, function (on) { includeDataLoad = on; setRoot(rootId); },
        '끄면(기본) 데이터 적재 구문(dataLoad=true: SQL 스크립트의 리터럴 INSERT를 파일·테이블 단위로 묶은 것)으로 가는 줄을 뺀다. 표준 산출물의 export.includeDataLoad와 같다');
      dataLoadCheck.el.classList.add('include-data-load');
      // 데이터 적재 구문이 없는 그래프에서는 고를 것이 없다.
      if (!index.nodesOfKind('SQL_STATEMENT').some(function (n) { return n.attrs.dataLoad === true; })) dataLoadCheck.el.style.display = 'none';
      var screensCheck = ui.createCheck('화면 이동 펼치기', false, function (on) { expandScreens = on; },
        '모두 펼칠 때 화면 이동(컨트롤러 → 화면의 RENDERS, redirect·forward로 넘어가는 핸들러) 너머까지 펼친다. 끄면 기준 노드의 흐름만 펼치고 거기서 멈춘다');
      screensCheck.el.classList.add('expand-screens');
      var layoutsCheck = ui.createCheck('레이아웃 펼치기', false, function (on) { expandLayouts = on; setRoot(rootId); },
        '끄면(기본) 레이아웃(sitemesh 데코레이터, tiles template과 조각)은 한 줄만 보이고, 위로 갈 때는 그 레이아웃을 쓰는 화면을 "쓰는 화면 n개" 한 줄로 접는다');
      layoutsCheck.el.classList.add('expand-layouts');
      var routesCheck = ui.createCheck('화면 내 이동 숨기기 (' + ui.formatCount(routeCount) + ')', hideRoutes, function (on) { hideRoutes = on; setRoot(rootId); },
        '켜면(그런 요청이 있으면 기본) 화면 내 이동(clientRoute=true: React Router의 navigate·Link 같은 클라이언트 라우트. 표준 산출물의 [route] 줄)을 접는다. 서버로 가는 요청이 아니라서 엔드포인트로 이어지지 않는 잎이다. 접은 수는 줄의 "접힘"에 적힌다');
      routesCheck.el.classList.add('hide-client-routes');
      // 클라이언트 라우트가 없는 그래프에서는 고를 것이 없다.
      if (!routeCount) routesCheck.el.style.display = 'none';

      var callers = createTreePane(app, 'callers', '호출하는 쪽', '이 노드에 이르는 경로');
      var callees = createTreePane(app, 'callees', '호출되는 쪽', '이 노드에서 나가는 경로');

      function setRoot(id) {
        rootId = id;
        var node = id ? index.node(id) : null;
        if (id) {
          rootBox.replaceChildren(
            node ? ui.kindBadge(node.kind) : h('span', { class: 'tag warn', text: '노드 없음' }),
            h('button', { class: 'link name', type: 'button', text: node && node.kind === 'HTTP_ENDPOINT' ? G.endpointLabel(node, id) : index.label(id), title: id,
              on: { click: function () { app.showDetail(id); } } })
          );
        }
        var opts = { includeColumns: includeColumns, allImplementations: allImplementations,
          showAccessors: showAccessors, showExternal: showExternal, includeDataLoad: includeDataLoad, expandLayouts: expandLayouts, hideClientRoutes: hideRoutes };
        callers.setRoot(id, opts);
        callees.setRoot(id, opts);
      }
      var entries = createEntryGroups(app, function (id) { app.navigate('calltree', { id: id }); });
      function depth() {
        var d = parseInt(depthInput.value, 10);
        return d > 0 ? Math.min(d, 50) : 12;
      }

      var head = h('div', { class: 'pane-head' }, [
        picker.el, entries.el, rootBox, h('span', { class: 'spacer' }),
        columnsCheck.el, implementationsCheck.el, accessorsCheck.el, externalCheck.el, dataLoadCheck.el, screensCheck.el, layoutsCheck.el, routesCheck.el,
        h('span', { class: 'muted', text: '깊이' }), depthInput,
        h('button', { class: 'btn expand-all', type: 'button', text: '모두 펼치기', on: { click: function () { callers.expandAll(depth(), expandScreens, expandLayouts); callees.expandAll(depth(), expandScreens, expandLayouts); } } }),
        h('button', { class: 'btn', type: 'button', text: '접기', on: { click: function () { callers.collapse(); callees.collapse(); } } })
      ]);

      return {
        el: h('div', { class: 'view view-calltree', attrs: { style: 'flex-direction:column' } }, [
          h('div', { class: 'pane calltree-head', attrs: { style: 'flex:none' } }, [head, entries.panel]),
          h('div', { class: 'trees' }, [callers.el, callees.el])
        ]),
        show: function (params) {
          if (params && params.id && params.id !== rootId) setRoot(params.id);
          callers.refresh();
          callees.refresh();
        }
      };
    }
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);
