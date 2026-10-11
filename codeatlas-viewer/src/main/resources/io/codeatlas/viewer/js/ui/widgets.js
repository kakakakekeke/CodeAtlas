// 공통 부품: 가상 목록, 노드 고르기, 작은 입력 부품.
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var G = CA.graph;
  var ui = CA.ui;
  var h = ui.h;
  var ROW_HEIGHT = 28;

  /**
   * 고정 행 높이 가상 목록. 보이는 행만 DOM에 둔다.
   * opts.render(item, index) → 요소. opts.className: 목록 요소에 더할 클래스.
   */
  function createVirtualList(opts) {
    var items = [];
    var spacer = h('div', { class: 'vlist-spacer' });
    var rows = h('div', { class: 'vlist-rows' });
    var el = h('div', { class: 'vlist' + (opts.className ? ' ' + opts.className : ''), attrs: { tabindex: '0' } }, [spacer, rows]);
    var pending = false;

    function update() {
      pending = false;
      var range = CA.vlist.visibleRange({
        scrollTop: el.scrollTop, viewportHeight: el.clientHeight || 600, rowHeight: ROW_HEIGHT, count: items.length, overscan: 8
      });
      spacer.style.height = range.totalHeight + 'px';
      rows.style.transform = 'translateY(' + range.offsetY + 'px)';
      var out = [];
      for (var i = range.start; i < range.end; i++) out.push(opts.render(items[i], i));
      rows.replaceChildren.apply(rows, out);
    }
    function schedule() {
      if (pending) return;
      pending = true;
      root.requestAnimationFrame(update);
    }
    el.addEventListener('scroll', schedule);
    if (root.ResizeObserver) new root.ResizeObserver(schedule).observe(el);

    return {
      el: el,
      setItems: function (list, keepScroll) {
        items = list || [];
        if (!keepScroll) el.scrollTop = 0;
        update();
      },
      refresh: update,
      count: function () { return items.length; },
      /** index 행이 보이게 스크롤한다. */
      reveal: function (index) {
        var top = index * ROW_HEIGHT;
        var height = el.clientHeight || 600;
        // 보이는 범위 밖이면 가운데쯤에 오게 한다.
        if (top < el.scrollTop || top + ROW_HEIGHT > el.scrollTop + height) el.scrollTop = Math.max(0, top - Math.floor(height / 2));
        update();
      }
    };
  }

  /** 노드 한 줄: 종류, 짧은 이름, 전체 이름. */
  function nodeRow(node, extraClass) {
    var declared = node.attrs.declared !== false;
    var shortName = G.shortLabel(node, node.id);
    var full = node.qualifiedName || '';
    return h('div', { class: 'vrow' + (extraClass ? ' ' + extraClass : ''), title: node.id }, [
      ui.kindBadge(node.kind),
      h('span', { class: 'name' + (declared ? '' : ' undeclared'), text: shortName }),
      h('span', { class: 'qn', text: full === shortName ? '' : full })
    ]);
  }

  /**
   * 이름으로 노드를 찾아 고르는 입력.
   * opts.index, opts.kinds(고를 수 있는 종류 배열), opts.placeholder, opts.onPick(node).
   */
  function createPicker(opts) {
    var LIMIT = 40;
    var input = h('input', { class: 'input', type: 'search', placeholder: opts.placeholder || '이름으로 찾기', attrs: { autocomplete: 'off', spellcheck: 'false' } });
    var list = h('div', { class: 'picker-list' });
    var el = h('div', { class: 'picker' }, [input, list]);
    var results = [];
    var active = -1;

    function close() { el.classList.remove('open'); active = -1; }
    function pick(node) {
      close();
      input.value = '';
      opts.onPick(node);
    }
    function render() {
      var shown = results.slice(0, LIMIT);
      var children = shown.map(function (n, i) {
        var row = nodeRow(n, i === active ? 'selected' : '');
        // mousedown에서 골라 입력의 blur보다 먼저 처리한다.
        row.addEventListener('mousedown', function (e) { e.preventDefault(); pick(n); });
        return row;
      });
      if (!results.length) children.push(h('div', { class: 'more', text: '맞는 것이 없다' }));
      else if (results.length > LIMIT) children.push(h('div', { class: 'more', text: '외 ' + ui.formatCount(results.length - LIMIT) + '개. 검색어를 더 적는다.' }));
      list.replaceChildren.apply(list, children);
    }
    var refresh = ui.debounce(function () {
      results = CA.search.search(opts.index, { text: input.value, kinds: opts.kinds });
      active = results.length ? 0 : -1;
      el.classList.add('open');
      render();
    }, 120);

    input.addEventListener('input', refresh);
    input.addEventListener('focus', refresh);
    input.addEventListener('blur', function () { root.setTimeout(close, 100); });
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') { close(); return; }
      if (!el.classList.contains('open')) return;
      var max = Math.min(results.length, LIMIT) - 1;
      if (e.key === 'ArrowDown') { active = Math.min(max, active + 1); render(); e.preventDefault(); }
      else if (e.key === 'ArrowUp') { active = Math.max(0, active - 1); render(); e.preventDefault(); }
      else if (e.key === 'Enter' && active >= 0 && results[active]) { pick(results[active]); e.preventDefault(); }
    });
    return { el: el, input: input };
  }

  /** 여러 개 중 하나를 고르는 버튼 묶음. options: [[값, 글자]]. */
  function createSegmented(options, value, onChange) {
    var current = value;
    var buttons = options.map(function (o) {
      return h('button', { type: 'button', text: o[1], attrs: { 'aria-pressed': String(o[0] === current), 'data-value': o[0] }, on: {
        click: function () { set(o[0]); onChange(o[0]); }
      } });
    });
    function set(v) {
      current = v;
      buttons.forEach(function (b, i) { b.setAttribute('aria-pressed', String(options[i][0] === v)); });
    }
    return { el: h('div', { class: 'seg' }, buttons), get: function () { return current; }, set: set };
  }

  function createCheck(label, checked, onChange, title) {
    var input = h('input', { type: 'checkbox', checked: !!checked });
    input.addEventListener('change', function () { onChange(input.checked); });
    return { el: h('label', { class: 'check', title: title }, [input, label]), input: input };
  }

  /** 고른 노드들을 지울 수 있는 꼬리표로 보여 준다. */
  function renderChips(container, index, ids, onRemove) {
    var chips = ids.map(function (id) {
      var node = index.node(id);
      return h('span', { class: 'chip', title: id }, [
        G.shortLabel(node, id),
        h('button', { type: 'button', text: '×', title: '빼기', on: { click: function () { onRemove(id); } } })
      ]);
    });
    container.replaceChildren.apply(container, chips);
  }

  /**
   * 상세의 뒤로·앞으로 단추와 그 자취. 단추를 누르면 자취를 옮기고 go(id)를 부른다(그 노드를 다시 그리는 일은 부르는 쪽이 한다).
   * 자취를 바꾼 뒤에는 update()로 단추를 맞춘다. 결과: { trail, back, forward, update }.
   */
  function createTrailNav(index, go) {
    var trail = CA.trail.create();
    function button(cls, text, step) {
      return h('button', { class: 'btn small ' + cls, type: 'button', text: text, on: { click: function () {
        var id = step < 0 ? trail.back() : trail.forward();
        if (id == null) return;
        update();
        go(id);
      } } });
    }
    var back = button('nav-back', '← 뒤로', -1);
    var forward = button('nav-forward', '앞으로 →', 1);
    function set(el, word, id) {
      el.disabled = id == null;
      el.setAttribute('title', id == null ? word + ' 갈 노드가 없다' : word + ': ' + index.label(id));
    }
    function update() {
      set(back, '뒤로', trail.peek(-1));
      set(forward, '앞으로', trail.peek(1));
    }
    update();
    return { trail: trail, back: back, forward: forward, update: update };
  }

  ui.ROW_HEIGHT = ROW_HEIGHT;
  ui.createTrailNav = createTrailNav;
  ui.createVirtualList = createVirtualList;
  ui.nodeRow = nodeRow;
  ui.createPicker = createPicker;
  ui.createSegmented = createSegmented;
  ui.createCheck = createCheck;
  ui.renderChips = renderChips;
})(typeof globalThis !== 'undefined' ? globalThis : this);
