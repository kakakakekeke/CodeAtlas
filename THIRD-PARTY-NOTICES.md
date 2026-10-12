# 서드파티 고지

CodeAtlas 자체는 [Apache License 2.0](LICENSE)을 따른다. 이 저장소의 소스에는 다른 프로젝트의 코드가 들어 있지 않다. 다만 빌드하면 아래 소프트웨어를 Maven Central에서 받아 산출물에 묶는다.

- `codeatlas.jar`(실행 jar, `codeatlas-cli/target/`): 아래 표의 전부.
- `viewer.html`(`codeatlas analyze`·`codeatlas export`가 만드는 뷰어): Mermaid.

산출물을 다른 사람에게 줄 때는 이 파일과 `LICENSE`, `NOTICE`를 함께 준다. `viewer.html`은 Mermaid의 고지를 파일 머리의 주석에 담고 있으므로 그 주석을 지우지 않으면 된다.

버전은 `pom.xml`(루트, `codeatlas-web`, `codeatlas-viewer`)에 있다. 외부 라이브러리를 더하거나 빼면 이 파일도 함께 고친다.

## 묶이는 소프트웨어

| 소프트웨어 | Maven 좌표 | 라이선스 | 소스 |
|---|---|---|---|
| Jackson | `com.fasterxml.jackson.core`: `jackson-databind`, `jackson-core`, `jackson-annotations` | Apache-2.0 | https://github.com/FasterXML/jackson-databind |
| Eclipse JDT Core, ECJ | `org.eclipse.jdt`: `org.eclipse.jdt.core`, `ecj` | EPL-2.0 | https://github.com/eclipse-jdt/eclipse.jdt.core |
| Eclipse Platform, Equinox | `org.eclipse.platform`: `org.eclipse.core.*`, `org.eclipse.equinox.*`, `org.eclipse.osgi`, `org.eclipse.text` | EPL-2.0 | https://github.com/eclipse-platform/eclipse.platform, https://github.com/eclipse-platform/eclipse.platform.ui, https://github.com/eclipse-equinox/equinox |
| OSGi | `org.osgi`: `org.osgi.service.prefs`, `osgi.annotation` | Apache-2.0 | https://github.com/osgi/osgi |
| JNA | `net.java.dev.jna`: `jna`, `jna-platform` | Apache-2.0 OR LGPL-2.1-or-later | https://github.com/java-native-access/jna |
| tree-sitter-ng (Java 바인딩) | `io.github.bonede`: `tree-sitter`, `tree-sitter-javascript`, `tree-sitter-typescript`, `tree-sitter-tsx` | MIT | https://github.com/bonede/tree-sitter-ng |
| tree-sitter 런타임과 JavaScript·TypeScript 문법 | 위 바인딩 jar의 네이티브 라이브러리(`lib/`)에 들어 있다 | MIT | https://github.com/tree-sitter/tree-sitter, https://github.com/tree-sitter/tree-sitter-javascript, https://github.com/tree-sitter/tree-sitter-typescript |
| Mermaid | `org.webjars.npm:mermaid`의 `dist/mermaid.min.js` | MIT | https://github.com/mermaid-js/mermaid |

Eclipse Platform과 JNA, OSGi는 Eclipse JDT Core가 끌어오는 전이 의존이다.

## Apache-2.0으로 쓰는 것

Jackson, OSGi, JNA. 라이선스 전문은 이 저장소의 [LICENSE](LICENSE)와 같다.

JNA는 Apache-2.0과 LGPL-2.1-or-later 가운데 고를 수 있고, CodeAtlas는 Apache-2.0으로 쓴다.

`jackson-core`에는 아래 코드가 들어 있다. 고지와 라이선스 전문은 `codeatlas.jar`의 `META-INF/NOTICE`, `META-INF/FastDoubleParser-LICENSE`, `META-INF/FastDoubleParser-ThirdParty-LICENSE`, `META-INF/Schubfach-LICENSE`에 있다.

- FastDoubleParser (MIT): Copyright (c) 2024 Werner Randelshofer, Switzerland. https://github.com/wrandelshofer/FastDoubleParser — 그 안에 든 다른 코드의 라이선스(Boost Software License 1.0, MIT)는 `FastDoubleParser-ThirdParty-LICENSE`에 있다.
- Schubfach (MIT): Copyright 2018-2020 Raffaello Giulietti. https://github.com/c4f7fcce9cb06515/Schubfach

Jackson의 `NOTICE`:

```
# Jackson JSON processor

Jackson is a high-performance, Free/Open Source JSON processing library.
It was originally written by Tatu Saloranta (tatu.saloranta@iki.fi), and has
been in development since 2007.
It is currently developed by a community of developers.

## Copyright

Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)

## Licensing

Jackson 2.x core and extension components are licensed under Apache License 2.0
To find the details that apply to this artifact see the accompanying LICENSE file.

## Credits

A list of contributors may be found from CREDITS(-2.x) file, which is included
in some artifacts (usually source distributions); but is always available
from the source code management (SCM) system project uses.
```

## EPL-2.0으로 쓰는 것

Eclipse JDT Core, ECJ, Eclipse Platform, Equinox. CodeAtlas는 이들을 고치지 않고 받은 그대로 묶는다.

- 이 구성요소에는 CodeAtlas의 Apache-2.0이 아니라 Eclipse Public License 2.0이 적용된다. 전문: https://www.eclipse.org/legal/epl-2.0/
- 소스 코드는 EPL-2.0으로 공개되어 있고 위 표의 저장소에서 받을 수 있다.
- 각 jar에 든 저작권·라이선스 표기는 지우지 않는다. 실행 jar를 만들 때 빼는 것은 서명 파일(`META-INF/*.SF` 등)과 `module-info.class`뿐이다.

## MIT로 쓰는 것

저작권 표기:

- Mermaid: Copyright (c) 2014 - 2022 Knut Sveidqvist
- tree-sitter-ng: Copyright (c) 2018-2023 Wang Liang
- tree-sitter: Copyright (c) 2018 Max Brunsfeld
- tree-sitter-javascript: Copyright (c) 2014 Max Brunsfeld
- tree-sitter-typescript: Copyright (c) 2017 Max Brunsfeld

Mermaid의 표기는 `pom.xml`에 적힌 버전의 webjar에 든 `LICENSE`에서, 나머지는 2026-10-12에 각 저장소 기본 브랜치의 `LICENSE`에서 옮겼다. 허가 문구는 다섯 모두 같다.

```
The MIT License (MIT)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Mermaid 번들에 함께 든 것

`mermaid.min.js`는 Mermaid가 의존하는 다른 오픈소스 라이브러리를 한 파일로 합친 번들이다. 그 가운데 DOMPurify(Apache-2.0 OR MPL-2.0)처럼 번들 안에 `@license` 주석이 남아 있는 것이 있다.

- [확인 필요] 번들에 든 라이브러리 전체와 각각의 라이선스. Mermaid 저장소의 `package.json`에서 확인해야 한다.

## `codeatlas.jar` 안의 고지

2026-10-12에 빌드한 `codeatlas.jar`를 열어 확인한 것이다.

- 들어 있는 것: `META-INF/NOTICE`(Jackson·OSGi 등의 고지를 합친 것), `META-INF/AL2.0`과 `META-INF/LGPL2.1`(JNA), `META-INF/FastDoubleParser-LICENSE`, `META-INF/FastDoubleParser-ThirdParty-LICENSE`, `META-INF/Schubfach-LICENSE`, `about.html`과 `about_files/`(Eclipse, EPL-2.0을 가리킨다), `io/codeatlas/viewer/vendor/mermaid.LICENSE`.
- 들어 있지 않은 것: CodeAtlas의 `LICENSE`와 `NOTICE`, 이 파일, tree-sitter 계열의 MIT 고지(원래 jar에도 없다), EPL-2.0 전문.

그래서 jar만 따로 줄 때는 `LICENSE`, `NOTICE`, 이 파일을 함께 주어야 한다. 이 셋을 jar 안에 넣는 일은 아직 하지 않았다.
