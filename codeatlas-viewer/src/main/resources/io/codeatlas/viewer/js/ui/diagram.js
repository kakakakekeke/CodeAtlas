// 다이어그램 영역: 내장한 Mermaid로 그리고, 끌어서 옮기고 휠로 확대한다.
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var ui = CA.ui;
  var h = ui.h;
  var doc = root.document;
  var sequence = 0;
  var mermaidState = 'idle'; // idle | ready | missing | failed

  /** HTML에 내장한 Mermaid를 처음 쓸 때 한 번 실행한다. 없으면 null. 네트워크에서 받아 오지 않는다. */
  function loadMermaid() {
    if (mermaidState === 'ready') return root.mermaid;
    if (mermaidState === 'missing' || mermaidState === 'failed') return null;
    var holder = doc.getElementById('codeatlas-vendor-mermaid');
    var text = holder ? holder.textContent : '';
    if (!text || !text.trim()) { mermaidState = 'missing'; return null; }
    try {
      var script = doc.createElement('script');
      script.text = JSON.parse(text);
      doc.head.appendChild(script);
      holder.textContent = '';
    } catch (e) {
      root.console.error('Mermaid를 실행하지 못했다: ' + e);
    }
    if (root.mermaid && typeof root.mermaid.render === 'function') { mermaidState = 'ready'; return root.mermaid; }
    mermaidState = 'failed';
    return null;
  }

  function isDark() {
    return !!(root.matchMedia && root.matchMedia('(prefers-color-scheme: dark)').matches);
  }

  /**
   * opts.onNodeClick(nodeId): 다이어그램의 타입·테이블을 눌렀을 때.
   * 돌려주는 값: { toolbar(단추 묶음), canvas, source(소스 보기 영역), render(source, aliases, extra), clear(message) }
   */
  function createDiagram(opts) {
    var stage = h('div', { class: 'dg-stage' });
    var message = h('div', { class: 'dg-message' });
    var canvas = h('div', { class: 'dg-canvas' }, [stage, message]);
    var sourceBox = h('pre', { class: 'dg-source' });
    var view = { x: 0, y: 0, scale: 1 };
    var size = { w: 0, h: 0 };
    var current = { source: '', aliases: null, extra: null, token: 0 };

    function applyView() {
      stage.style.transform = 'translate(' + view.x + 'px,' + view.y + 'px) scale(' + view.scale + ')';
    }
    function fit() {
      if (!size.w || !size.h) return;
      var cw = canvas.clientWidth || 800;
      var ch = canvas.clientHeight || 600;
      view.scale = Math.min(1, (cw - 32) / size.w, (ch - 32) / size.h);
      if (!(view.scale > 0)) view.scale = 1;
      view.x = Math.max(16, (cw - size.w * view.scale) / 2);
      view.y = Math.max(16, (ch - size.h * view.scale) / 2);
      applyView();
    }
    function zoomAt(factor, cx, cy) {
      var next = Math.min(4, Math.max(0.05, view.scale * factor));
      var k = next / view.scale;
      view.x = cx - (cx - view.x) * k;
      view.y = cy - (cy - view.y) * k;
      view.scale = next;
      applyView();
    }
    function zoomCenter(factor) {
      zoomAt(factor, (canvas.clientWidth || 800) / 2, (canvas.clientHeight || 600) / 2);
    }

    canvas.addEventListener('wheel', function (e) {
      e.preventDefault();
      var rect = canvas.getBoundingClientRect();
      zoomAt(Math.exp(-e.deltaY * 0.0015), e.clientX - rect.left, e.clientY - rect.top);
    }, { passive: false });

    var drag = null;
    canvas.addEventListener('pointerdown', function (e) {
      if (e.button !== 0) return;
      drag = { x: e.clientX, y: e.clientY, vx: view.x, vy: view.y, moved: false };
    });
    canvas.addEventListener('pointermove', function (e) {
      if (!drag) return;
      var dx = e.clientX - drag.x;
      var dy = e.clientY - drag.y;
      if (!drag.moved && Math.abs(dx) + Math.abs(dy) < 4) return;
      if (!drag.moved) {
        drag.moved = true;
        canvas.classList.add('dragging');
        if (canvas.setPointerCapture && e.pointerId != null) { try { canvas.setPointerCapture(e.pointerId); } catch (err) { /* 잡지 못해도 끌기는 된다 */ } }
      }
      view.x = drag.vx + dx;
      view.y = drag.vy + dy;
      applyView();
    });
    function endDrag(e) {
      var wasDrag = drag && drag.moved;
      drag = null;
      canvas.classList.remove('dragging');
      if (wasDrag || !e || e.type !== 'pointerup') return;
      // 끌지 않고 눌렀다 뗀 것이면 누른 도형의 노드를 연다.
      var target = e.target && e.target.closest ? e.target.closest('[data-node-id]') : null;
      if (target && opts.onNodeClick) opts.onNodeClick(target.getAttribute('data-node-id'));
    }
    canvas.addEventListener('pointerup', endDrag);
    canvas.addEventListener('pointercancel', endDrag);

    function setMessage(text) {
      message.textContent = text || '';
      message.style.display = text ? 'flex' : 'none';
    }
    function showSource(open) {
      sourceBox.classList.toggle('open', open);
      sourceButton.setAttribute('aria-pressed', String(open));
    }

    /** Mermaid가 그린 타입·테이블 도형에 노드 ID를 달아 누를 수 있게 한다. */
    function bindNodes(aliases) {
      if (!aliases) return;
      var shapes = stage.querySelectorAll('g[id]');
      for (var i = 0; i < shapes.length; i++) {
        var nodeId = CA.diagram.nodeIdOfShape(shapes[i].getAttribute('id'), aliases);
        if (!nodeId) continue;
        shapes[i].setAttribute('data-node-id', nodeId);
        shapes[i].classList.add('ca-clickable');
      }
    }

    /**
     * 점선 테두리는 뷰어가 그린 뒤에 직접 입힌다. SVG로 저장해도 남는다.
     * Mermaid는 클래스 도형에 stroke-dasharray를 입히지 않고, erDiagram에서는 classDef 값의 공백을 지워 엉뚱한 점선이 된다.
     * external: 소스 없는 타입(클래스 다이어그램). undeclared: DDL 없는 테이블(ERD).
     */
    function dashOutlines() {
      var outlines = stage.querySelectorAll('g.external .label-container path, g.external > rect, g.undeclared > rect, g.undeclared .outer-path path, g.undeclared > g > path');
      for (var i = 0; i < outlines.length; i++) {
        outlines[i].style.setProperty('stroke-dasharray', '5 4', 'important');
      }
    }

    /** 방향을 모르는 추론 관계(directionKnown=false)의 선에서 카디널리티 표시(선 끝 모양)를 지운다. Mermaid 문법으로는 표시 없는 선을 쓸 수 없다. */
    function stripMarkers(undirected) {
      if (!undirected || !undirected.length) return;
      var wanted = new Set(undirected);
      var lines = stage.querySelectorAll('path[id]');
      for (var i = 0; i < lines.length; i++) {
        var n = CA.diagram.relationIndexOfEdgeId(lines[i].getAttribute('id'));
        if (n == null || !wanted.has(n)) continue;
        lines[i].removeAttribute('marker-start');
        lines[i].removeAttribute('marker-end');
        lines[i].classList.add('ca-undirected');
      }
    }

    function draw() {
      var token = ++current.token;
      var source = current.source;
      sourceBox.textContent = source;
      stage.replaceChildren();
      size.w = size.h = 0;
      if (!source) return;
      var mermaid = loadMermaid();
      if (!mermaid) {
        setMessage('');
        showSource(true);
        banner('이 파일에는 Mermaid가 들어 있지 않아 그림 대신 Mermaid 소스를 보여 준다.');
        return;
      }
      banner('');
      setMessage('그리는 중…');
      var id = 'ca-diagram-' + (++sequence);
      var dark = isDark();
      try {
        mermaid.initialize({
          startOnLoad: false, securityLevel: 'strict', theme: dark ? 'dark' : 'neutral',
          maxTextSize: 5000000, maxEdges: 20000,
          fontFamily: 'ui-sans-serif, -apple-system, "Segoe UI", "Malgun Gothic", sans-serif',
          class: { useMaxWidth: false }, er: { useMaxWidth: false }
        });
      } catch (e) { /* 설정을 못 바꿔도 그리기는 시도한다 */ }
      Promise.resolve().then(function () { return mermaid.render(id, source); }).then(function (result) {
        if (token !== current.token) return;
        stage.innerHTML = result.svg;
        var svg = stage.querySelector('svg');
        if (svg) {
          var box = (svg.getAttribute('viewBox') || '').split(/[\s,]+/).map(Number);
          if (box.length === 4 && box[2] > 0 && box[3] > 0) {
            size.w = box[2]; size.h = box[3];
            svg.setAttribute('width', String(box[2]));
            svg.setAttribute('height', String(box[3]));
          } else {
            var rect = svg.getBoundingClientRect();
            size.w = rect.width; size.h = rect.height;
          }
          svg.style.maxWidth = 'none';
        }
        setMessage('');
        bindNodes(current.aliases);
        dashOutlines();
        stripMarkers(current.extra && current.extra.undirected);
        fit();
      }).catch(function (err) {
        if (token !== current.token) return;
        var leftover = doc.getElementById('d' + id);
        if (leftover) leftover.remove();
        setMessage('');
        showSource(true);
        banner('Mermaid가 이 소스를 그리지 못했다: ' + (err && err.message ? err.message : err), true);
      });
    }

    var bannerEl = h('div', { class: 'banner', attrs: { style: 'display:none' } });
    function banner(text, isError) {
      bannerEl.textContent = text || '';
      bannerEl.className = 'banner' + (isError ? ' error' : '');
      bannerEl.style.display = text ? '' : 'none';
    }

    var sourceButton = h('button', { class: 'btn small', type: 'button', text: '소스', title: 'Mermaid 소스 보기', attrs: { 'aria-pressed': 'false' }, on: {
      click: function () { showSource(!sourceBox.classList.contains('open')); }
    } });
    var copyButton = h('button', { class: 'btn small', type: 'button', text: '소스 복사', on: {
      click: function () { if (current.source) ui.copyText(current.source).then(function () { ui.flash(copyButton, '복사함'); }); }
    } });
    var saveButton = h('button', { class: 'btn small', type: 'button', text: 'SVG 저장', on: {
      click: function () {
        var svg = stage.querySelector('svg');
        if (!svg) return;
        var text = new root.XMLSerializer().serializeToString(svg);
        ui.download((opts.fileName || 'diagram') + '.svg', 'image/svg+xml', text);
      }
    } });
    var toolbar = h('div', { attrs: { style: 'display:flex;gap:4px;align-items:center' } }, [
      h('button', { class: 'btn small', type: 'button', text: '−', title: '축소', on: { click: function () { zoomCenter(1 / 1.25); } } }),
      h('button', { class: 'btn small', type: 'button', text: '+', title: '확대', on: { click: function () { zoomCenter(1.25); } } }),
      h('button', { class: 'btn small', type: 'button', text: '맞춤', title: '화면에 맞추기', on: { click: fit } }),
      sourceButton, copyButton, saveButton
    ]);

    if (root.matchMedia) {
      var mq = root.matchMedia('(prefers-color-scheme: dark)');
      if (mq.addEventListener) mq.addEventListener('change', function () { if (current.source) draw(); });
    }

    return {
      toolbar: toolbar,
      banner: bannerEl,
      canvas: canvas,
      source: sourceBox,
      /** extra.undirected: 끝 표시를 지울 관계의 순번(core/erd.js toMermaid의 결과). */
      render: function (source, aliases, extra) {
        current.source = source;
        current.aliases = aliases || null;
        current.extra = extra || null;
        draw();
      },
      clear: function (text) {
        current.token++;
        current.source = '';
        current.aliases = null;
        current.extra = null;
        sourceBox.textContent = '';
        stage.replaceChildren();
        size.w = size.h = 0;
        banner('');
        setMessage(text);
      },
      fit: fit
    };
  }

  ui.createDiagram = createDiagram;
})(typeof globalThis !== 'undefined' ? globalThis : this);
