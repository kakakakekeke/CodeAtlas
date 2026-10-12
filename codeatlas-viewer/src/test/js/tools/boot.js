'use strict';
// 조립한 viewer.html의 스크립트를 가짜 DOM에서 실행한다. 화면 테스트가 함께 쓴다.
const vm = require('node:vm');
const { scriptBlocks } = require('./assemble.js');
const { createWindow } = require('./fake-dom.js');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * 창에서 HTML의 스크립트를 실행한다. 뷰어는 내장 블록을 푼 뒤에 켜지므로 돌아온 시점에는 아직 "푸는 중"이다.
 * head에 붙인 script는 브라우저처럼 그 자리에서 실행한다(뷰어가 Mermaid를 이렇게 실행한다).
 */
function run(window, blocks) {
  const context = vm.createContext(window);
  const head = window.document.head;
  const append = head.appendChild.bind(head);
  head.appendChild = (el) => {
    const added = append(el);
    if (el.tagName === 'SCRIPT' && el.text) vm.runInContext(el.text, context, { filename: 'appended-script.js' });
    return added;
  };
  blocks.filter((b) => !b.type).forEach((b, i) => {
    vm.runInContext(b.text, context, { filename: 'viewer-script-' + i + '.js' });
  });
  return window;
}

/** HTML의 내장 블록(type이 있는 script)을 { id: 글자 }로 꺼낸다. */
function dataBlocks(html) {
  const data = {};
  scriptBlocks(html).filter((b) => b.type).forEach((b) => { data[b.id] = b.text; });
  return data;
}

/** HTML의 스크립트를 가짜 DOM에서 실행하고, 켜지기를 기다리지 않고 창을 돌려준다. */
function launch(html, opts) {
  return run(createWindow(dataBlocks(html), opts), scriptBlocks(html));
}

/** HTML의 스크립트를 가짜 DOM에서 실행하고 뷰어가 켜진(또는 켜지지 못했다고 알린) 뒤의 창을 돌려준다. */
async function boot(html, opts) {
  const window = launch(html, opts);
  await window.CodeAtlas.ready;
  return window;
}

module.exports = { boot, launch, run, dataBlocks, sleep };
