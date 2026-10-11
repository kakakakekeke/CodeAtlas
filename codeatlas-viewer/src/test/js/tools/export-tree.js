'use strict';
// 뷰어의 콜트리를 표준 산출물의 텍스트 트리(codeatlas-export의 CallTreeRenderer.text, 위치 없이)와 같은 모양으로 적는다. 테스트 전용이다.
// 뷰어는 같은 노드로 가는 줄을 관계 이름별로 따로 보이므로(READS와 WRITES 등) 표준 산출물의 한 걸음(link.stepKey)으로 묶어 한 줄로 만든다.
const { core } = require('../helpers.js');
const notation = require('./export-notation.js');

const calltree = core('calltree');
const RANK = { RESOLVED: 0, INFERRED: 1, UNRESOLVED: 2 };

/** 자식 줄들을 표준 산출물의 걸음 단위로 묶는다(처음 나온 순서). [{ key, id, items, next }] next는 아래로 내려갈 때 쓸 줄(쓰기가 먼저다). */
function steps(ix, treeNode) {
  const groups = new Map();
  calltree.expand(ix, treeNode).forEach((k) => {
    const key = k.link.stepKey;
    if (!groups.has(key)) groups.set(key, { key, id: k.id, items: [] });
    groups.get(key).items.push(k);
  });
  return Array.from(groups.values()).map((g) => {
    g.next = g.items.find((k) => k.link.kind === 'WRITES') || g.items[0];
    return g;
  });
}

/** 걸음 하나의 표식(표준 산출물의 Marker 순서): impl, via interface, trigger?, ?, reflect, before. */
function markersOf(group) {
  const any = (f) => group.items.some((k) => f(k.link));
  const out = [];
  if (any((l) => l.impl)) out.push('impl');
  if (any((l) => l.viaInterface)) out.push('via interface');
  if (any((l) => l.triggerUncertain)) out.push('trigger?');
  if (group.items.every((k) => k.link.receiverUncertain)) out.push('?');
  const detail = detailOf(group);
  if (detail === 'reflection') out.push('reflect');
  if (detail === 'modelAttribute') out.push('before');
  if (detail === 'redirect') out.push('redirect');
  if (detail === 'forward') out.push('forward');
  if (detail === 'layout') out.push('layout');
  return out;
}

/** 호출 방식·역할·모듈 표시(link.detail)를 걸음 단위로 합친 것: 사전순으로 앞선 것. */
function detailOf(group) {
  let detail = null;
  group.items.forEach((k) => {
    const d = k.link.detail;
    if (d != null && (detail == null || d < detail)) detail = d;
  });
  return detail;
}

function confidenceOf(group) {
  let best = 'UNRESOLVED';
  group.items.forEach((k) => { if (RANK[k.link.confidence] < RANK[best]) best = k.link.confidence; });
  return best;
}

/** 표준 산출물이 표식으로 적는 세부 표시(괄호로는 적지 않는다). */
const MARKER_DETAILS = ['reflection', 'modelAttribute', 'redirect', 'forward', 'layout'];

/** 그래프의 노드가 아닌 줄(오버로드 후보 묶음, 레이아웃을 쓰는 화면 수)이면 그 정보, 아니면 null. */
function syntheticOf(group) {
  return group.items[0].synthetic || null;
}

function line(ix, group) {
  let s = '';
  const confidence = confidenceOf(group);
  if (confidence === 'INFERRED') s += '~ ';
  else if (confidence === 'UNRESOLVED') s += '? ';
  const synthetic = syntheticOf(group);
  // 후보 엔드포인트 묶음의 이름은 표준 산출물처럼 그 요청의 표시 이름이다.
  // 자기 페이지로 내는 폼의 후보 묶음은 요청 문구를 되풀이하지 않는다("후보 엔드포인트").
  if (synthetic && synthetic.matchesOf) return s + '[ambiguous ' + synthetic.count + '] ' + (synthetic.selfSubmit ? synthetic.text : notation.label(ix, synthetic.matchesOf));
  if (synthetic) return s + (synthetic.type === 'ambiguous' ? '[ambiguous ' + synthetic.count + '] ' : '[layout] ') + synthetic.text;
  markersOf(group).forEach((m) => { s += '[' + m + '] '; });
  if (group.items[0].shared > 0) s += '[shared] ';
  // 요청 문맥에서 풀어 쓴 이름(미해결 selfSubmit 대상)은 뷰어가 준 것을 쓴다.
  const own = group.items[0].labelText;
  s += own != null ? '[' + notation.kindTag(ix, group.id) + '] ' + own : notation.tagged(ix, group.id);
  const kind = notation.kindOf(ix, group.id);
  let detail = kind === 'DB_TABLE' || kind === 'DB_VIEW' ? notation.detailOf(ix, group.items) : detailOf(group);
  if (MARKER_DETAILS.includes(detail)) detail = null;
  // 다른 방언에서 보충한 구문, 공통 화면을 포함하는 화면 수(표준 산출물은 세부 표시 뒤에 쉼표로 잇는다)
  const note = calltree.noteOf(group.items[0]);
  if (note) detail = detail ? detail + ', ' + note : note;
  if (detail) s += ' (' + detail + ')';
  return s;
}

/** 표준 산출물이 기본으로 그 아래를 펼치지 않는 줄인지: 컨트롤러가 그리는 화면, redirect·forward로 넘어간 핸들러, 레이아웃. */
function stopsAt(t, limits) {
  const l = limits || {};
  if (!t.link) return false;
  if (calltree.isLayoutLink(t.link) && t.direction !== 'callers') return !l.expandLayouts;
  if (l.expandScreens) return false;
  return (t.direction !== 'callers' && t.link.kind === 'RENDERS') || calltree.isRedirectLink(t.link) || calltree.isForwardLink(t.link);
}

/**
 * id에서 시작한 트리를 끝까지 펼쳐 표준 산출물의 텍스트 트리로 적는다(위치 없음, [see above] 없음).
 * opts는 뷰어의 콜트리 옵션 그대로다. limits.expandScreens가 참이 아니면 컨트롤러가 그리는 화면 아래는 펼치지 않는다(표준 산출물의 기본값).
 */
function text(ix, id, direction, opts, limits) {
  const l = limits || {};
  const out = [];
  const root = calltree.createRoot(ix, id, direction, opts || {});
  out.push(notation.tagged(ix, id));
  (function walk(t, childPrefix, depth) {
    if (depth > (l.maxDepth || 20)) return;
    if (stopsAt(t, l)) return;
    const groups = steps(ix, t);
    groups.forEach((g, i) => {
      const last = i === groups.length - 1;
      out.push(childPrefix + (last ? '└─ ' : '├─ ') + line(ix, g) + (g.next.cycle ? ' [cycle]' : ''));
      if (!g.next.cycle) walk(g.next, childPrefix + (last ? '   ' : '│  '), depth + 1);
    });
  })(root, '', 0);
  return out.join('\n') + '\n';
}

module.exports = { steps, markersOf, detailOf, confidenceOf, line, text, stopsAt, syntheticOf, MARKER_DETAILS };
