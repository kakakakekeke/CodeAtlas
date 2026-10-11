'use strict';
// 테스트와 개발용 조립기. ViewerExporter(Java)와 같은 규칙으로 template.html에 자산을 끼워 넣는다.
//   node assemble.js <graph.json> <out.html> [mermaid.min.js]
const fs = require('node:fs');
const path = require('node:path');

const RESOURCE_ROOT = path.join(__dirname, '..', '..', '..', 'main', 'resources', 'io', 'codeatlas', 'viewer');

function read(rel) {
  return fs.readFileSync(path.join(RESOURCE_ROOT, rel), 'utf8');
}

/** assets.txt 에 적힌 순서대로 자산 경로를 돌려준다. */
function assetList() {
  return read('assets.txt').split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
}

function escapeJsonForHtml(json) {
  return json.replace(/</g, '\\u003c');
}

function assemble(graphJson, mermaidSource) {
  const assets = assetList();
  const css = assets.filter((a) => a.endsWith('.css')).map(read).join('\n');
  const js = assets.filter((a) => a.endsWith('.js')).map((a) => '// ---- ' + a + '\n' + read(a)).join('\n');
  const template = read('template.html');
  const parts = {
    '/*@@CSS@@*/': css,
    '/*@@JS@@*/': js,
    '@@DATA@@': escapeJsonForHtml(graphJson),
    '@@MERMAID@@': mermaidSource ? escapeJsonForHtml(JSON.stringify(mermaidSource)) : ''
  };
  // 자리 표시는 템플릿에서만 찾는다. 끼워 넣은 내용 안에 같은 글자가 있어도 건드리지 않는다.
  const found = Object.keys(parts).map((marker) => {
    const at = template.indexOf(marker);
    if (at < 0) throw new Error('template.html에 ' + marker + ' 자리가 없다');
    return { marker, at };
  }).sort((a, b) => a.at - b.at);
  let html = '';
  let pos = 0;
  for (const f of found) {
    html += template.substring(pos, f.at) + parts[f.marker];
    pos = f.at + f.marker.length;
  }
  html += template.substring(pos);
  return html;
}

/** HTML에서 <script> 블록을 꺼낸다. [{ attrs, id, type, text }] */
function scriptBlocks(html) {
  const out = [];
  const re = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi;
  let m;
  while ((m = re.exec(html))) {
    const id = /\bid="([^"]*)"/.exec(m[1]);
    const type = /\btype="([^"]*)"/.exec(m[1]);
    out.push({ attrs: m[1], id: id ? id[1] : '', type: type ? type[1] : '', text: m[2] });
  }
  return out;
}

module.exports = { RESOURCE_ROOT, read, assetList, assemble, scriptBlocks };

if (require.main === module) {
  const [graphPath, outPath, mermaidPath] = process.argv.slice(2);
  if (!graphPath || !outPath) {
    console.error('사용법: node assemble.js <graph.json> <out.html> [mermaid.min.js]');
    process.exit(2);
  }
  const html = assemble(fs.readFileSync(graphPath, 'utf8'), mermaidPath ? fs.readFileSync(mermaidPath, 'utf8') : '');
  fs.mkdirSync(path.dirname(path.resolve(outPath)), { recursive: true });
  fs.writeFileSync(outPath, html);
  console.log(outPath + ' (' + html.length + '자)');
}
