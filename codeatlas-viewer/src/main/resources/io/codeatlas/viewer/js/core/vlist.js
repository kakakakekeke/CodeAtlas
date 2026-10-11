// 가상 스크롤의 범위 계산. DOM을 쓰지 않는다.
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};

  /**
   * 고정 행 높이 목록에서 지금 그려야 할 행 범위 [start, end)와 그 앞의 빈 높이(offsetY)를 계산한다.
   * overscan은 보이는 범위 위아래로 더 그릴 행 수.
   */
  function visibleRange(o) {
    var count = Math.max(0, o.count | 0);
    var rowHeight = o.rowHeight;
    var totalHeight = count * rowHeight;
    if (count === 0) return { start: 0, end: 0, offsetY: 0, totalHeight: 0 };
    var overscan = o.overscan == null ? 5 : o.overscan;
    var maxScroll = Math.max(0, totalHeight - o.viewportHeight);
    var scrollTop = Math.min(Math.max(0, o.scrollTop || 0), maxScroll);
    var first = Math.floor(scrollTop / rowHeight);
    var visible = Math.ceil(o.viewportHeight / rowHeight);
    var start = Math.max(0, first - overscan);
    var end = Math.min(count, first + visible + overscan);
    return { start: start, end: end, offsetY: start * rowHeight, totalHeight: totalHeight };
  }

  var api = { visibleRange: visibleRange };
  CA.vlist = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
