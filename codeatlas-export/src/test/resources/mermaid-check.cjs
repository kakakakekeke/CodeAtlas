// 생성한 Mermaid 텍스트를 실제 mermaid 파서(mermaid.parse)로 확인하는 스크립트. 단위 테스트는 이 스크립트 없이 돈다.
//
// 쓰는 법:
//   1) mvn -q -pl codeatlas-export test -Dcodeatlas.mermaid.dump=<폴더>   (테스트가 검증한 텍스트를 <폴더>에 남긴다)
//   2) node codeatlas-export/src/test/resources/mermaid-check.cjs <mermaid.js 또는 mermaid.min.js(UMD 번들)> <폴더>/*.mmd
//
// 브라우저용 번들을 DOM 없이 돌린다. DOMPurify는 DOM이 없으면 sanitize가 없는 함수 객체가 되므로,
// 함수 프로토타입에 통과 함수를 둬서 파서의 의미 동작이 죽지 않게 한다(문법 확인에는 영향이 없다).
const fs = require('fs');
const vm = require('vm');

const bundle = process.argv[2];
const files = process.argv.slice(3);
if (!bundle || files.length === 0) {
  console.error('usage: node mermaid-check.cjs <mermaid.js> <file.mmd>...');
  process.exit(2);
}
Function.prototype.sanitize = function (s) { return s; };
Function.prototype.addHook = function () {};
Function.prototype.removeHook = function () {};
vm.runInThisContext(fs.readFileSync(bundle, 'utf8'), { filename: 'mermaid.js' });
const mermaid = globalThis.mermaid;
if (!mermaid) {
  console.error('mermaid 전역 객체를 찾지 못했다');
  process.exit(2);
}
(async () => {
  let failed = 0;
  for (const file of files) {
    try {
      const result = await mermaid.parse(fs.readFileSync(file, 'utf8'));
      console.log('OK   ' + file + ' (' + (result && result.diagramType) + ')');
    } catch (e) {
      failed++;
      console.log('FAIL ' + file + '\n     ' + String((e && e.message) || e).split('\n').slice(0, 6).join('\n     '));
    }
  }
  console.log(failed === 0 ? 'ALL OK (' + files.length + ')' : failed + ' FAILED of ' + files.length);
  process.exit(failed ? 1 : 0);
})();
