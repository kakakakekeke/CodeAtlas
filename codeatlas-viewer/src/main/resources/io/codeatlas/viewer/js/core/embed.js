// 내장 블록 풀기: viewer.html에 gzip으로 압축하고 base64로 감싸 넣은 글자를 되돌린다. DOM을 쓰지 않는다.
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};

  var UNSUPPORTED = '이 브라우저에는 압축을 푸는 기능(DecompressionStream)이 없다. Chrome·Edge 80, Firefox 113, Safari 16.4 이상에서 연다.';

  /** base64 글자를 바이트로 바꾼다. 줄바꿈과 공백은 건너뛴다. */
  function bytesFromBase64(text) {
    var binary = root.atob(text);
    var bytes = new root.Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  /**
   * base64로 감싼 gzip을 UTF-8 글자로 푼다. Promise를 돌려준다.
   * 브라우저에 내장된 DecompressionStream만 쓴다: file:로 연 문서에서도 되고 네트워크·워커를 쓰지 않는다.
   */
  function inflateText(base64) {
    return new root.Promise(function (resolve, reject) {
      if (typeof root.DecompressionStream !== 'function') throw new Error(UNSUPPORTED);
      var stream = new root.DecompressionStream('gzip');
      var writer = stream.writable.getWriter();
      var reader = stream.readable.getReader();
      var decoder = new root.TextDecoder('utf-8');
      var parts = [];
      // 쓰는 쪽의 실패는 읽는 쪽에서도 같은 예외로 나오므로 거기서 한 번만 알린다.
      var ignore = function () {};
      writer.write(bytesFromBase64(base64)).catch(ignore);
      writer.close().catch(ignore);
      (function pump() {
        reader.read().then(function (chunk) {
          if (chunk.done) {
            parts.push(decoder.decode());
            resolve(parts.join(''));
            return;
          }
          parts.push(decoder.decode(chunk.value, { stream: true }));
          pump();
        }).catch(reject);
      })();
    });
  }

  var api = { bytesFromBase64: bytesFromBase64, inflateText: inflateText };
  CA.embed = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
