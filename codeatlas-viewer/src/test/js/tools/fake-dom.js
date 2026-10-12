'use strict';
// 뷰어의 DOM 스크립트를 Node에서 한 번 돌려 보기 위한 아주 작은 가짜 DOM.
// 배치·그리기는 하지 않는다. 뷰어가 쓰는 API만 흉내 낸다.

// 실제 DOMTokenList처럼 빈 토큰과 공백이 든 토큰을 거부한다. 브라우저는 여기서 예외를 던져
// 뷰어 시작을 멈추므로, 가짜 DOM이 너그러우면 그 버그를 테스트가 놓친다.
function checkToken(method, token) {
  const t = String(token);
  if (t === '') throw new SyntaxError(`Failed to execute '${method}' on 'DOMTokenList': The token provided must not be empty.`);
  if (/\s/.test(t)) {
    throw new Error(`Failed to execute '${method}' on 'DOMTokenList': The token provided ('${t}') contains HTML space characters, which are not valid in tokens.`);
  }
}

class ClassList {
  constructor(el) { this.el = el; this.set = new Set(); }
  add(...names) { names.forEach((n) => { checkToken('add', n); this.set.add(n); }); }
  remove(...names) { names.forEach((n) => { checkToken('remove', n); this.set.delete(n); }); }
  contains(n) { return this.set.has(n); }
  toggle(n, force) {
    checkToken('toggle', n);
    const on = force === undefined ? !this.set.has(n) : !!force;
    if (on) this.set.add(n); else this.set.delete(n);
    return on;
  }
  toString() { return [...this.set].join(' '); }
}

class FakeNode {
  constructor() { this.parentNode = null; this.childNodes = []; }
  get textContent() { return this.childNodes.map((c) => c.textContent).join(''); }
  contains(other) {
    for (let n = other; n; n = n.parentNode) if (n === this) return true;
    return false;
  }
}

class FakeText extends FakeNode {
  constructor(text) { super(); this.data = String(text); this.nodeType = 3; }
  get textContent() { return this.data; }
  set textContent(v) { this.data = String(v); }
}

class FakeElement extends FakeNode {
  constructor(tag, doc) {
    super();
    this.nodeType = 1;
    this.ownerDocument = doc;
    this.tagName = String(tag).toUpperCase();
    this.attributes = {};
    this.classList = new ClassList(this);
    this.dataset = {};
    this.style = { setProperty(k, v) { this[k] = v; }, removeProperty(k) { delete this[k]; } };
    this.listeners = {};
    this.value = '';
    this.checked = false;
    this.disabled = false;
    this.scrollTop = 0;
    this.scrollLeft = 0;
    this.clientHeight = 20000; // 가상 목록이 테스트에서 모든 행을 그리게 넉넉히 둔다
    this.clientWidth = 900;
    this._html = '';
  }
  get id() { return this.attributes.id || ''; }
  set id(v) { this.attributes.id = String(v); }
  get className() { return this.classList.toString(); }
  set className(v) { this.classList.set = new Set(String(v).split(/\s+/).filter(Boolean)); }
  get children() { return this.childNodes.filter((c) => c.nodeType === 1); }
  get firstChild() { return this.childNodes[0] || null; }
  get textContent() { return this._html ? this._html.replace(/<[^>]*>/g, '') : super.textContent; }
  set textContent(v) {
    this._html = '';
    this.childNodes.forEach((c) => { c.parentNode = null; });
    this.childNodes = [];
    if (v !== '' && v != null) this.appendChild(new FakeText(v));
  }
  get innerHTML() { return this._html; }
  set innerHTML(v) { this.textContent = ''; this._html = String(v); }
  get offsetHeight() { return this.clientHeight; }
  get offsetWidth() { return this.clientWidth; }
  get scrollHeight() { return this.clientHeight; }

  _adopt(node) {
    if (typeof node === 'string') node = new FakeText(node);
    if (node.parentNode) node.parentNode.removeChild(node);
    node.parentNode = this;
    return node;
  }
  appendChild(node) { node = this._adopt(node); this.childNodes.push(node); return node; }
  // 브라우저처럼 null·undefined도 글자("null")로 붙는다: 화면 코드가 없는 자식을 그대로 넘기면 테스트에서 드러난다.
  append(...nodes) { nodes.forEach((n) => { this.appendChild(n == null ? String(n) : n); }); }
  prepend(...nodes) { nodes.reverse().forEach((n) => { n = this._adopt(n); this.childNodes.unshift(n); }); }
  insertBefore(node, ref) {
    node = this._adopt(node);
    const i = ref ? this.childNodes.indexOf(ref) : -1;
    if (i < 0) this.childNodes.push(node); else this.childNodes.splice(i, 0, node);
    return node;
  }
  removeChild(node) {
    const i = this.childNodes.indexOf(node);
    if (i >= 0) { this.childNodes.splice(i, 1); node.parentNode = null; }
    return node;
  }
  remove() { if (this.parentNode) this.parentNode.removeChild(this); }
  replaceChildren(...nodes) { this.textContent = ''; this.append(...nodes); }

  setAttribute(k, v) {
    this.attributes[k] = String(v);
    if (k === 'class') this.className = v;
    if (k.indexOf('data-') === 0) this.dataset[k.substring(5).replace(/-(\w)/g, (m, c) => c.toUpperCase())] = String(v);
  }
  getAttribute(k) {
    if (k === 'class') return this.className;
    return Object.prototype.hasOwnProperty.call(this.attributes, k) ? this.attributes[k] : null;
  }
  hasAttribute(k) { return this.getAttribute(k) !== null; }
  removeAttribute(k) { delete this.attributes[k]; }

  addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
  removeEventListener(type, fn) { this.listeners[type] = (this.listeners[type] || []).filter((f) => f !== fn); }
  dispatchEvent(event) {
    if (!event.target) event.target = this;
    event.currentTarget = this;
    (this.listeners[event.type] || []).slice().forEach((fn) => fn.call(this, event));
    if (event.bubbles !== false && !event._stopped && this.parentNode && this.parentNode.dispatchEvent) this.parentNode.dispatchEvent(event);
    return !event.defaultPrevented;
  }
  /** 테스트에서 이벤트를 일으킬 때 쓴다. */
  fire(type, props) {
    const event = Object.assign({
      type, bubbles: true, defaultPrevented: false, target: this,
      preventDefault() { this.defaultPrevented = true; },
      stopPropagation() { this._stopped = true; }
    }, props || {});
    this.dispatchEvent(event);
    return event;
  }
  click() { this.fire('click', { button: 0 }); }
  focus() { this.ownerDocument.activeElement = this; this.fire('focus', { bubbles: false }); }
  blur() { this.fire('blur', { bubbles: false }); }
  select() {}
  scrollIntoView() {}
  setPointerCapture() {}
  releasePointerCapture() {}
  getBoundingClientRect() { return { left: 0, top: 0, right: this.clientWidth, bottom: this.clientHeight, width: this.clientWidth, height: this.clientHeight }; }

  matches(selector) { return selector.split(',').some((s) => matchCompound(this, s.trim())); }
  closest(selector) {
    for (let n = this; n && n.nodeType === 1; n = n.parentNode) if (n.matches(selector)) return n;
    return null;
  }
  querySelectorAll(selector) {
    const out = [];
    const groups = selector.split(',').map((s) => s.trim().split(/\s+/));
    const walk = (el) => {
      el.children.forEach((c) => {
        if (groups.some((parts) => matchChain(c, parts, this))) out.push(c);
        walk(c);
      });
    };
    walk(this);
    return out;
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
}

/** tag, .class, #id, [attr], [attr="값"] 을 붙여 쓴 단순 선택자 하나를 맞춰 본다. */
function matchCompound(el, compound) {
  const re = /([.#]?)([\w-]+)|\[([\w-]+)(?:(\^?=)"?([^"\]]*)"?)?\]/g;
  let m;
  while ((m = re.exec(compound))) {
    if (m[3]) {
      const v = el.getAttribute(m[3]);
      if (v === null) return false;
      if (m[4] === '=' && v !== m[5]) return false;
      if (m[4] === '^=' && v.indexOf(m[5]) !== 0) return false;
    } else if (m[1] === '.') {
      if (!el.classList.contains(m[2])) return false;
    } else if (m[1] === '#') {
      if (el.id !== m[2]) return false;
    } else if (el.tagName !== m[2].toUpperCase()) return false;
  }
  return true;
}

/** 공백으로 이은 자손 선택자. */
function matchChain(el, parts, scope) {
  if (!matchCompound(el, parts[parts.length - 1])) return false;
  let i = parts.length - 2;
  for (let n = el.parentNode; n && i >= 0 && n.nodeType === 1; n = n.parentNode) {
    if (matchCompound(n, parts[i])) i--;
    if (n === scope) break;
  }
  return i < 0;
}

/**
 * viewer.html 본문을 흉내 낸 창을 만든다.
 * dataBlocks: { 요소 ID: 텍스트 } — 내장 블록(<script type="text/plain">)들.
 * opts.noDecompression: DecompressionStream이 없는 브라우저를 흉내 낸다.
 */
function createWindow(dataBlocks, opts) {
  const o = opts || {};
  const errors = [];
  const document = {
    activeElement: null,
    title: '',
    listeners: {},
    createElement(tag) { return new FakeElement(tag, document); },
    createElementNS(ns, tag) { return new FakeElement(tag, document); },
    createTextNode(text) { return new FakeText(text); },
    addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); },
    removeEventListener() {},
    dispatchEvent(event) { (this.listeners[event.type] || []).slice().forEach((fn) => fn(event)); },
    getElementById(id) { return document.documentElement.querySelectorAll('#' + id)[0] || null; },
    querySelector(sel) { return document.documentElement.querySelector(sel); },
    querySelectorAll(sel) { return document.documentElement.querySelectorAll(sel); },
    execCommand() { return true; }
  };
  document.documentElement = new FakeElement('html', document);
  document.head = document.documentElement.appendChild(new FakeElement('head', document));
  document.body = document.documentElement.appendChild(new FakeElement('body', document));
  const app = document.body.appendChild(new FakeElement('div', document));
  app.id = 'app';
  Object.keys(dataBlocks || {}).forEach((id) => {
    const el = document.body.appendChild(new FakeElement('script', document));
    el.id = id;
    el.setAttribute('type', 'text/plain');
    el.textContent = dataBlocks[id];
  });

  const windowListeners = {};
  const window = {
    document,
    console: { log() {}, warn() {}, error(...args) { errors.push(args.map(String).join(' ')); } },
    location: { hash: o.hash || '', href: 'file:///viewer.html' },
    history: { replaceState(state, title, url) { window.location.hash = String(url).substring(String(url).indexOf('#')); } },
    navigator: { clipboard: { writeText(text) { window.copied = text; return Promise.resolve(); } } },
    addEventListener(type, fn) { (windowListeners[type] = windowListeners[type] || []).push(fn); },
    removeEventListener() {},
    fire(type, event) { (windowListeners[type] || []).slice().forEach((fn) => fn(Object.assign({ type }, event || {}))); },
    matchMedia() { return { matches: !!o.dark, addEventListener() {}, addListener() {} }; },
    getComputedStyle() { return { getPropertyValue() { return ''; } }; },
    setTimeout, clearTimeout, setInterval, clearInterval,
    requestAnimationFrame(fn) { return setTimeout(fn, 0); },
    cancelAnimationFrame(id) { clearTimeout(id); },
    ResizeObserver: class { observe() {} disconnect() {} },
    Blob: class { constructor(parts, options) { this.parts = parts; this.options = options; } },
    URL: { createObjectURL() { return 'blob:fake'; }, revokeObjectURL() {} },
    JSON, Math, Date, Map, Set, Promise, Array, Object, String, Number, Error, RegExp, Symbol, parseInt, parseFloat, isNaN, encodeURIComponent, decodeURIComponent,
    URLSearchParams,
    // 내장 블록을 푸는 데 쓰는 것(core/embed.js). file:에서도 막히지 않는 API다.
    atob, Uint8Array, TextDecoder,
    DecompressionStream: o.noDecompression ? undefined : DecompressionStream,
    errors
  };
  window.window = window;
  window.self = window;
  window.globalThis = window;
  return window;
}

module.exports = { createWindow, FakeElement };
