// 상세에서 지나온 노드의 자취(뒤로·앞으로). DOM을 쓰지 않는다.
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};

  /**
   * 자취를 만든다. 칸마다 노드 ID 하나다.
   * visit(id, loose): 새로 간다. 앞으로 갈 칸은 버린다. loose(목록에서 고름)로 온 칸에서 다시 loose로 가면 칸을 늘리지 않고 그 칸을 바꾼다
   *   (목록을 훑는 동안 자취가 쌓이지 않게). 결과: 'push' | 'replace' | 'same'(지금 노드와 같다).
   * sync(id): 주소가 바뀌어 온 이동(브라우저의 뒤로·앞으로 가기). 바로 앞·뒤 칸의 노드면 그리로 옮기고('back' | 'forward'), 아니면 visit(id, false)와 같다.
   * back(), forward(): 한 칸 옮기고 그 노드 ID를 준다. 더 없으면 옮기지 않고 null.
   * peek(step): 옮기지 않고 step 칸 떨어진 노드 ID(없으면 null). current()는 peek(0).
   */
  function create() {
    var entries = [];   // [{ id, loose }]
    var at = -1;

    function peek(step) {
      var entry = entries[at + step];
      return entry ? entry.id : null;
    }
    function move(step) {
      if (peek(step) == null) return null;
      at += step;
      return entries[at].id;
    }
    function visit(id, loose) {
      var current = entries[at];
      if (current && current.id === id) return 'same';
      var replace = !!(current && current.loose && loose);
      entries.length = replace ? at : at + 1;
      entries.push({ id: id, loose: !!loose });
      at = entries.length - 1;
      return replace ? 'replace' : 'push';
    }
    function sync(id) {
      if (peek(0) === id) return 'same';
      if (peek(-1) === id) { at--; return 'back'; }
      if (peek(1) === id) { at++; return 'forward'; }
      return visit(id, false);
    }

    return {
      visit: visit,
      sync: sync,
      back: function () { return move(-1); },
      forward: function () { return move(1); },
      peek: peek,
      current: function () { return peek(0); },
      canBack: function () { return peek(-1) != null; },
      canForward: function () { return peek(1) != null; }
    };
  }

  var api = { create: create };
  CA.trail = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
