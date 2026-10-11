'use strict';
// 조립한 viewer.html의 스크립트를 가짜 DOM에서 실행한다. 화면 테스트가 함께 쓴다.
const vm = require('node:vm');
const { scriptBlocks } = require('./assemble.js');
const { createWindow } = require('./fake-dom.js');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** HTML의 스크립트를 가짜 DOM에서 실행하고 창을 돌려준다. */
function boot(html, opts) {
  const blocks = scriptBlocks(html);
  const data = {};
  blocks.filter((b) => b.type === 'application/json').forEach((b) => { data[b.id] = b.text; });
  const window = createWindow(data, opts);
  const context = vm.createContext(window);
  blocks.filter((b) => b.type !== 'application/json').forEach((b, i) => {
    vm.runInContext(b.text, context, { filename: 'viewer-script-' + i + '.js' });
  });
  return window;
}

module.exports = { boot, sleep };
