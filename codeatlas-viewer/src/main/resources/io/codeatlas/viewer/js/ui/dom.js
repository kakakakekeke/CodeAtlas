// DOM 도우미.
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var G = CA.graph;
  var doc = root.document;

  /**
   * 요소를 만든다. props: class, text, title, attrs({이름: 값}), on({이벤트: 처리기}), 그 밖의 키는 요소 속성으로 넣는다.
   * children: 노드, 문자열, null이 섞인 배열.
   */
  function h(tag, props, children) {
    var el = doc.createElement(tag);
    var p = props || {};
    Object.keys(p).forEach(function (k) {
      var v = p[k];
      if (v == null) return;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = v;
      else if (k === 'attrs') Object.keys(v).forEach(function (a) { if (v[a] != null) el.setAttribute(a, v[a]); });
      else if (k === 'on') Object.keys(v).forEach(function (type) { el.addEventListener(type, v[type]); });
      else if (k === 'title') el.setAttribute('title', v);
      else el[k] = v;
    });
    (children || []).forEach(function (c) {
      if (c == null || c === false) return;
      el.appendChild(typeof c === 'string' ? doc.createTextNode(c) : c);
    });
    return el;
  }

  function kindBadge(kind) {
    var meta = G.kindMeta(kind);
    return h('span', { class: 'badge g-' + meta.group, text: meta.short, title: meta.label + ' (' + kind + ')' });
  }

  /** 신뢰도 표시. RESOLVED는 조용한 체크 표시로, 나머지는 눈에 띄게. */
  function confBadge(confidence, quietResolved) {
    var c = confidence || 'RESOLVED';
    if (c === 'RESOLVED' && quietResolved) return h('span', { class: 'conf RESOLVED', text: '✓', title: 'RESOLVED' });
    return h('span', { class: 'conf ' + c, text: c, title: '신뢰도: ' + c });
  }

  function debounce(fn, ms) {
    var timer = null;
    return function () {
      var args = arguments;
      var self = this;
      root.clearTimeout(timer);
      timer = root.setTimeout(function () { fn.apply(self, args); }, ms);
    };
  }

  function copyText(text) {
    var nav = root.navigator;
    if (nav && nav.clipboard && nav.clipboard.writeText) {
      return nav.clipboard.writeText(text).catch(function () { legacyCopy(text); });
    }
    legacyCopy(text);
    return Promise.resolve();
  }

  function legacyCopy(text) {
    var area = h('textarea', { value: text, attrs: { style: 'position:fixed;left:-9999px;top:0' } });
    doc.body.appendChild(area);
    area.focus();
    area.select();
    try { doc.execCommand('copy'); } catch (e) { /* 복사를 못 해도 화면은 그대로 둔다 */ }
    area.remove();
  }

  /** 내용을 파일로 내려받게 한다. 네트워크를 쓰지 않는다(Blob 주소). */
  function download(filename, mime, text) {
    var blob = new root.Blob([text], { type: mime });
    var url = root.URL.createObjectURL(blob);
    var a = h('a', { attrs: { href: url, download: filename, style: 'display:none' } });
    doc.body.appendChild(a);
    a.click();
    a.remove();
    root.setTimeout(function () { root.URL.revokeObjectURL(url); }, 1000);
  }

  /** 버튼 글자를 잠깐 바꿔 눌렸음을 알린다. */
  function flash(button, text) {
    var original = button.textContent;
    button.textContent = text;
    root.setTimeout(function () { button.textContent = original; }, 1200);
  }

  function formatCount(n) {
    return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }

  CA.ui = { h: h, kindBadge: kindBadge, confBadge: confBadge, debounce: debounce, copyText: copyText, download: download, flash: flash, formatCount: formatCount };
})(typeof globalThis !== 'undefined' ? globalThis : this);
