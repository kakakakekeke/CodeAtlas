'use strict';
// 표준 산출물(codeatlas-export가 쓴 파일)을 읽어 뷰어의 결과와 견줄 수 있는 모양으로 바꾼다. 테스트 전용이다.
//   bin/codeatlas analyze samples/sample-shop --out codeatlas-cli/target/final-out
// 다른 위치의 산출물은 환경 변수 CODEATLAS_OUT으로 준다.
const fs = require('node:fs');
const path = require('node:path');

const OUT_DIR = process.env.CODEATLAS_OUT
  || path.join(__dirname, '..', '..', '..', '..', '..', 'codeatlas-cli', 'target', 'final-out');

function file(rel) {
  return path.join(OUT_DIR, rel);
}
function exists(rel) {
  return fs.existsSync(file(rel));
}
function read(rel) {
  return fs.readFileSync(file(rel), 'utf8');
}

/** CSV(RFC 4180)를 칸의 배열들로 읽는다. 맨 앞의 BOM은 뗀다. */
function parseCsv(text) {
  const rows = [];
  let row = [];
  let cell = '';
  let quoted = false;
  const s = text.charCodeAt(0) === 0xFEFF ? text.substring(1) : text;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (quoted) {
      if (c === '"' && s[i + 1] === '"') { cell += '"'; i++; }
      else if (c === '"') quoted = false;
      else cell += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { row.push(cell); cell = ''; }
    else if (c === '\n') { row.push(cell); rows.push(row); row = []; cell = ''; }
    else if (c !== '\r') cell += c;
  }
  if (cell !== '' || row.length) { row.push(cell); rows.push(row); }
  return rows;
}

const LEADING = ['impl', 'via interface', 'trigger?', '?', 'reflect', 'before', 'redirect', 'forward', 'layout'];
const TRAILING = ['cycle', 'see above', 'depth limit'];
const TAGS = ['type', 'field', 'package', 'bean', 'endpoint', 'request', 'job', 'step', 'mapper', 'sql', 'fragment', 'table', 'view', 'column',
  'routine', 'procedure', 'function', 'trigger', 'db-package', 'sequence', 'synonym', 'module', 'js', 'jsp', 'file', 'unresolved', 'route', 'layout'];

/**
 * 콜트리 한 줄(`[~|?] [앞 표식] [종류] 이름 (세부)  file:line [뒤 표식]`)을 나눈다.
 * head는 `이름 (세부)`까지다. 이름에 괄호가 있을 수 있어 세부는 여기서 떼지 않는다(견주는 쪽에서 앞부분으로 맞춘다).
 */
function parseLine(text) {
  let rest = text;
  const node = { confidence: 'RESOLVED', markers: [], tag: '', head: '', location: null, children: [] };
  if (rest.startsWith('~ ')) { node.confidence = 'INFERRED'; rest = rest.substring(2); }
  else if (rest.startsWith('? ')) { node.confidence = 'UNRESOLVED'; rest = rest.substring(2); }
  for (let again = true; again;) {
    again = false;
    for (const m of LEADING) {
      if (rest.startsWith('[' + m + '] ')) { node.markers.push(m); rest = rest.substring(m.length + 3); again = true; }
    }
  }
  for (let again = true; again;) {
    again = false;
    for (const m of TRAILING) {
      if (rest.endsWith(' [' + m + ']')) { node.markers.push(m); rest = rest.substring(0, rest.length - m.length - 3); again = true; }
    }
  }
  for (const t of TAGS) {
    if (rest.startsWith('[' + t + '] ')) { node.tag = t; rest = rest.substring(t.length + 3); break; }
  }
  // 오버로드 후보를 묶은 줄: [ambiguous n] Type#name
  const group = /^\[(ambiguous \d+)\] /.exec(rest);
  if (!node.tag && group) { node.tag = group[1]; rest = rest.substring(group[0].length); }
  const at = rest.lastIndexOf('  ');
  if (at > 0) { node.location = rest.substring(at + 2); rest = rest.substring(0, at); }
  node.head = rest;
  return node;
}

/** `calltree/*.md`의 절(## 제목)마다 텍스트 트리를 읽는다. Map(제목 → 뿌리 노드). 노드 수 상한에 잘린 트리의 뿌리에는 truncated가 붙는다. */
function parseTrees(markdown) {
  const trees = new Map();
  const lines = markdown.split('\n');
  let title = null;
  let inBlock = false;
  let stack = [];
  for (const line of lines) {
    if (!inBlock && line.startsWith('## ')) { title = line.substring(3).trim(); continue; }
    if (line.startsWith('```')) { inBlock = !inBlock; stack = []; continue; }
    // 노드 수 상한에 닿은 트리는 끝에 안내 줄이 붙는다. 그 트리는 자식이 일부만 나온다.
    if (inBlock && title != null && line.startsWith('... ')) { if (trees.has(title)) trees.get(title).truncated = true; continue; }
    if (!inBlock || title == null) continue;
    // 들여쓰기는 세 글자 묶음("│  ", "   ", "├─ ", "└─ ")이다.
    let depth = 0;
    while (/^(│ {2}| {3}|├─ |└─ )/.test(line.substring(depth * 3))) depth++;
    const node = parseLine(line.substring(depth * 3));
    node.depth = depth;
    if (depth === 0) trees.set(title, node);
    else stack[depth - 1].children.push(node);
    stack[depth] = node;
    stack.length = depth + 1;
  }
  return trees;
}

/**
 * `erd/erd.mmd`의 관계 줄을 읽는다: `부모 ||..o{ 자식 : "라벨"`.
 * [{ parent, child, parentMarker, childMarker, dashed, label }] 엔티티 이름의 ` (DDL 없음)`, ` (VIEW)` 접미는 뗀다.
 */
function parseErdRelations(mmd) {
  const out = [];
  const name = '("[^"]*"|[A-Za-z_][A-Za-z0-9_]*)';
  const re = new RegExp('^\\s*' + name + ' (\\|\\||\\|o|\\}o|\\}\\|)(--|\\.\\.)(o\\{|o\\||\\|\\||\\|\\{) ' + name + ' : "(.*)"$');
  const clean = (n) => n.replace(/^"|"$/g, '').replace(/ \((DDL 없음|VIEW)\)$/, '');
  mmd.split('\n').forEach((line) => {
    const m = re.exec(line);
    if (m) out.push({ parent: clean(m[1]), parentMarker: m[2], dashed: m[3] === '..', childMarker: m[4], child: clean(m[5]), label: m[6] });
  });
  return out;
}

/** `erd/erd.mmd`에 그려진 엔티티 이름(접미 없이). */
function parseErdEntities(mmd) {
  const out = [];
  mmd.split('\n').forEach((line) => {
    const m = /^ {4}("[^"]*"|[A-Za-z_][A-Za-z0-9_]*)( \{)?$/.exec(line);
    if (m && m[1] !== 'erDiagram') out.push(m[1].replace(/^"|"$/g, '').replace(/ \((DDL 없음|VIEW)\)$/, ''));
  });
  return out;
}

/** report.md의 `## 제목 (N)` 절 본문을 줄 배열로 돌려준다. 없으면 null. */
function reportSection(markdown, titlePrefix) {
  const lines = markdown.split('\n');
  const start = lines.findIndex((l) => l.startsWith('## ' + titlePrefix));
  if (start < 0) return null;
  let end = lines.findIndex((l, i) => i > start && l.startsWith('## '));
  if (end < 0) end = lines.length;
  return lines.slice(start + 1, end);
}

/** 디렉터리 안의 파일 이름(이름순). 없으면 빈 배열. */
function list(rel) {
  return fs.existsSync(file(rel)) ? fs.readdirSync(file(rel)).sort() : [];
}

/**
 * 엔드포인트 콜트리 전부. 엔드포인트가 많으면 표준 산출물이 `calltree/endpoints/<컨트롤러>.md`로 나누고 `calltree/endpoints.md`는 색인으로 쓴다(F-015-R30).
 * 어느 쪽이든 Map(엔드포인트 표시 이름 → 뿌리 노드)로 돌려준다.
 */
function endpointTrees() {
  const split = list('calltree/endpoints').filter((f) => f.endsWith('.md'));
  if (!split.length) return parseTrees(read('calltree/endpoints.md'));
  const trees = new Map();
  split.forEach((f) => parseTrees(read('calltree/endpoints/' + f)).forEach((tree, title) => trees.set(title, tree)));
  return trees;
}

/** Markdown 표의 본문 행을 칸 배열로 읽는다(머리줄과 구분줄 제외). 칸의 백틱은 뗀다. */
function tableRows(lines) {
  return lines.filter((l) => l.startsWith('| ') && !/^\|\s*-{3}/.test(l)).slice(1)
    .map((l) => l.replace(/^\|/, '').replace(/\|\s*$/, '').split(' | ').map((c) => c.trim().replace(/^`|`$/g, '')));
}

module.exports = { OUT_DIR, file, exists, read, list, endpointTrees, tableRows, parseCsv, parseLine, parseTrees, parseErdRelations, parseErdEntities, reportSection };
