'use strict';
// 실제 브라우저에서만 드러난 시작 오류의 재현 테스트.
// 가짜 DOM의 classList가 공백이 든 토큰을 받아 주는 바람에, 클라이언트 라우트가 없는 그래프에서
// 탐색 화면이 classList.add('hide-client-routes none')로 예외를 던져 뷰어가 뜨지 않는 것을 놓쳤다.

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { assemble } = require('./tools/assemble.js');
const { boot } = require('./tools/boot.js');
const { createWindow } = require('./tools/fake-dom.js');
const { javaGraph } = require('./fixtures/java-graph.js');

test('F-016-AC82 가짜 DOM의 classList는 실제 DOMTokenList처럼 빈 토큰과 공백이 든 토큰을 거부한다', () => {
  const el = createWindow().document.createElement('div');
  assert.throws(() => el.classList.add('a b'), /contains HTML space characters/);
  assert.throws(() => el.classList.add(''), /must not be empty/);
  assert.throws(() => el.classList.toggle('a b'), /contains HTML space characters/);
  assert.throws(() => el.classList.remove('a\tb'), /contains HTML space characters/);
  el.classList.add('a', 'b');
  assert.equal(el.className, 'a b');
});

test('F-016-AC82 클라이언트 라우트가 없는 그래프에서도 뷰어가 켜지고, 탐색의 그 토글은 숨김(none) 클래스가 따로 붙는다', () => {
  const window = boot(assemble(JSON.stringify(javaGraph().graph()), ''));
  const doc = window.document;
  assert.equal(doc.querySelector('.fatal'), null, '시작 오류 화면이 없어야 한다');
  assert.ok(doc.querySelector('.view-explore').classList.contains('active'), '탐색 화면이 켜진다');
  const toggle = doc.querySelector('.view-explore .hide-client-routes');
  assert.ok(toggle, '토글 요소는 있다');
  assert.ok(toggle.classList.contains('hide-client-routes') && toggle.classList.contains('none'), toggle.className);
});
