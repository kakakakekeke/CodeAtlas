// 뷰어 시작점: 내장 데이터를 풀어 색인을 만들고 화면을 바꿔 가며 보여 준다.
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var ui = CA.ui;
  var h = ui.h;
  var doc = root.document;
  var VIEW_ORDER = ['explore', 'calltree', 'classdiagram', 'erd', 'crud', 'diagnostics'];

  /** 내장한 그래프를 풀어(gzip → 뷰어 내장 형식 → graph.json 모양) 돌려준다. 압축을 푸는 일이 비동기라서 Promise다. */
  function readGraph() {
    var holder = doc.getElementById('codeatlas-data');
    if (!holder) return Promise.reject(new Error('데이터 블록(codeatlas-data)이 없다'));
    var packed = holder.textContent;
    holder.textContent = '';
    return CA.embed.inflateText(packed).then(function (json) { return CA.compact.expand(JSON.parse(json)); });
  }

  function parseHash(hash) {
    var m = /^#\/([\w-]+)(?:\?(.*))?$/.exec(hash || '');
    if (!m || VIEW_ORDER.indexOf(m[1]) < 0) return null;
    var params = {};
    new root.URLSearchParams(m[2] || '').forEach(function (v, k) { params[k] = v; });
    return { view: m[1], params: params };
  }

  function formatHash(view, params) {
    var query = Object.keys(params || {}).filter(function (k) { return params[k] != null && params[k] !== ''; })
      .map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]); }).join('&');
    return '#/' + view + (query ? '?' + query : '');
  }

  function formatTime(iso) {
    if (!iso) return '';
    var d = new Date(iso);
    if (isNaN(d.getTime())) return String(iso);
    var p = function (n) { return (n < 10 ? '0' : '') + n; };
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }

  function start(graph) {
    var host = doc.getElementById('app');
    var index = CA.graph.buildIndex(graph);
    var views = {};      // 이름 → 만든 화면. 처음 열 때 만든다.
    var tabs = {};
    var current = null;
    var viewsBox = h('div', { class: 'views' });
    var drawerBody = h('div', { class: 'pane-body' });
    // 서랍 안에서 엣지를 따라 옮겨 간 노드의 자취. 서랍을 닫아도 남는다.
    var drawerNav = ui.createTrailNav(index, renderDrawer);
    var drawer = h('div', { class: 'drawer' }, [
      h('div', { class: 'pane-head' }, [h('span', { class: 'pane-title', text: '노드 상세' }), drawerNav.back, drawerNav.forward, h('span', { class: 'spacer' }),
        h('button', { class: 'btn small', type: 'button', text: '닫기', title: 'Esc', on: { click: closeDrawer } })]),
      drawerBody
    ]);

    function closeDrawer() { drawer.classList.remove('open'); }

    function renderDrawer(id) {
      ui.renderDetail(drawerBody, app, id, { inDrawer: true, follow: function (to) { openInDrawer(to, false); } });
    }

    /** 서랍에 노드를 연다. fromView: 화면의 줄을 눌러 온 것(연달아 누른 줄은 자취에 쌓지 않는다). */
    function openInDrawer(id, fromView) {
      drawerNav.trail.visit(id, fromView);
      drawerNav.update();
      renderDrawer(id);
      drawer.classList.add('open');
    }

    function activate(name, params) {
      if (!views[name]) {
        views[name] = CA.views[name].create(app);
        viewsBox.insertBefore(views[name].el, drawer);
      }
      VIEW_ORDER.forEach(function (v) {
        if (views[v]) views[v].el.classList.toggle('active', v === name);
        tabs[v].setAttribute('aria-selected', String(v === name));
      });
      current = name;
      closeDrawer();
      views[name].show(params || {});
    }

    var app = {
      index: index,
      /** 화면을 바꾼다. 주소의 # 부분도 맞춰서 뒤로 가기가 동작하게 한다. */
      navigate: function (view, params) {
        var hash = formatHash(view, params);
        if (root.location.hash !== hash) {
          try { root.location.hash = hash; } catch (e) { /* 주소를 못 바꿔도 화면은 바꾼다 */ }
        }
        activate(view, params);
      },
      /** 화면은 그대로 두고 주소에 새 항목을 쌓는다: 브라우저의 뒤로 가기가 이전 주소로 돌아간다. file:에서도 되는 location.hash만 쓴다. */
      pushHash: function (view, params) {
        var hash = formatHash(view, params);
        if (root.location.hash === hash) return;
        try { root.location.hash = hash; } catch (e) { /* 주소를 못 바꿔도 화면은 그대로 쓴다 */ }
      },
      /** 화면은 그대로 두고 주소만 맞춘다. */
      setHash: function (view, params) {
        var hash = formatHash(view, params);
        if (root.location.hash === hash) return;
        try { root.history.replaceState(null, '', hash); } catch (e) { /* file: 주소에서 막히면 그대로 둔다 */ }
      },
      /** 노드 상세를 연다. 탐색 화면에서는 그 자리에서, 다른 화면에서는 오른쪽 서랍에서. */
      showDetail: function (id) {
        if (current === 'explore') { views.explore.showDetail(id); return; }
        openInDrawer(id, true);
      }
    };
    CA.app = app;

    var project = index.project || {};
    var tabBar = h('div', { class: 'tabs', attrs: { role: 'tablist' } }, VIEW_ORDER.map(function (name) {
      var def = CA.views[name];
      var children = [def.title];
      // 탭에 붙이는 개수. 화면이 badges(app) → [{text, title, alert}]를 주면 그대로 보인다.
      if (def.badges) {
        def.badges(app).forEach(function (b) {
          children.push(h('span', { class: 'count' + (b.alert ? ' alert' : ''), text: b.text, title: b.title }));
        });
      }
      tabs[name] = h('button', { class: 'tab', type: 'button', attrs: { role: 'tab', 'aria-selected': 'false', 'data-view': name }, on: {
        click: function () { app.navigate(name, {}); }
      } }, children);
      return tabs[name];
    }));
    var topbar = h('div', { class: 'topbar' }, [
      h('div', { class: 'brand' }, [h('span', { class: 'brand-mark' }), 'CodeAtlas']),
      h('div', { class: 'project' }, [
        h('b', { text: project.name || '(이름 없는 프로젝트)', title: project.root || '' }),
        h('span', { text: '노드 ' + ui.formatCount(index.nodes.length) + ' · 엣지 ' + ui.formatCount(index.edges.length) }),
        project.analyzedAt ? h('span', { class: 'opt', text: '분석 ' + formatTime(project.analyzedAt), title: String(project.analyzedAt) }) : null
      ]),
      tabBar
    ]);
    viewsBox.appendChild(drawer);
    host.replaceChildren(topbar, viewsBox);
    if (project.name) doc.title = project.name + ' · CodeAtlas';

    doc.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeDrawer(); });
    root.addEventListener('hashchange', function () {
      var target = parseHash(root.location.hash);
      if (target) activate(target.view, target.params);
    });

    var initial = parseHash(root.location.hash) || { view: 'explore', params: {} };
    activate(initial.view, initial.params);
  }

  function fail(prefix, e) {
    var host = doc.getElementById('app');
    if (host) host.replaceChildren(h('p', { class: 'fatal', text: prefix + ': ' + (e && e.message ? e.message : e) }));
  }

  var loading = doc.getElementById('app');
  if (loading) loading.replaceChildren(h('p', { class: 'loading', text: '분석 결과를 푸는 중…' }));
  /** 뷰어가 켜지면(켜지지 못했다고 알린 뒤에도) 끝나는 Promise. */
  CA.ready = readGraph().then(function (graph) {
    try {
      start(graph);
    } catch (e) {
      root.console.error('뷰어를 시작하지 못했다: ' + (e && e.stack ? e.stack : e));
      fail('뷰어를 시작하지 못했다', e);
    }
  }, function (e) {
    fail('내장된 분석 결과를 읽지 못했다', e);
  });
})(typeof globalThis !== 'undefined' ? globalThis : this);
