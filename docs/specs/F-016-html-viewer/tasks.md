---
type: tasks
id: F-016
updated: 2026-10-12
---

# F-016 HTML 뷰어 작업 목록

JS 테스트: `node --test "codeatlas-viewer/src/test/js/*.test.js"` · Java 테스트: `mvn -q -pl codeatlas-viewer test`

개발 중에 화면을 보려면: `node codeatlas-viewer/src/test/js/tools/assemble.js <graph.json> <out.html> [mermaid.min.js]`

## 구현 (작업마다 테스트 먼저)
- [x] T1. 픽스처 그래프 작성 (`src/test/js/fixtures/sample-graph.js` → `src/test/resources/fixtures/sample-graph.json`)
- [x] T2. F-016-AC4, AC5, AC15 테스트 → 그래프 색인, 검색, 노드 상세, 가상 스크롤 범위 계산 (F-016-R4, R5, R14)
- [x] T3. F-016-AC6~AC9 테스트 → 콜트리 전개 (F-016-R6, R7, R8)
- [x] T4. F-016-AC10, AC16 테스트 → 클래스 다이어그램 범위 선택과 Mermaid 소스 (F-016-R9, R15)
- [x] T5. F-016-AC11, AC12, AC16 테스트 → ERD 범위 선택과 Mermaid 소스, 범례 (F-016-R10, R11, R15)
- [x] T6. F-016-AC13 테스트 → CRUD 집계 (F-016-R12)
- [x] T7. F-016-AC14 테스트 → 진단·미해결 목록 (F-016-R13)
- [x] T8. F-016-AC17, AC18 테스트 → 화면(HTML, CSS, DOM 스크립트)과 조립 (F-016-R16, R1, R2)
- [x] T9. F-016-AC1~AC3 테스트 → `ViewerExporter`, `META-INF/services` 등록, Mermaid webjar 인라인 (F-016-R1, R2, R3)
- [x] T10. (계획에 없던 작업) 브라우저에서 그려 보고 찾은 문제 수정: Mermaid 11이 도형 ID 앞에 다이어그램 ID를 붙여 도형 클릭이 안 되던 것, classDiagram의 `classDef`가 `-`로 시작하는 속성과 `stroke-dasharray`를 받지 못하던 것 (F-016-R9). 재현 테스트 `diagram.test.js`, `classdiagram.test.js`

- [x] T11. (계획에 없던 작업) `samples/sample-shop` 실제 분석 결과로 여섯 화면을 열어 보고 찾은 문제 수정 (F-016-AC19~AC27). 재현 테스트 `real-shapes.test.js`, `viewer-html.test.js`, `sample-shop.test.js`
  - 콜트리: JSP → 인라인 스크립트(`CONTAINS`)가 끊기던 것, 뷰 정의 쿼리·DB 패키지·트리거(`TRIGGERS_ON`, `op`와 `triggerEvent` 대조)를 따라가지 않던 것, `CALLS`와 `CONTAINS`가 겹쳐 같은 줄이 두 번 나오던 것, 스텝의 타입 줄과 메서드 줄 중복, 스텝 순서(`NEXT`), 간접 구현과 주입으로 좁히기, 화면 이동으로 "모두 펼치기"가 폭발하던 것, 외부 메서드가 이름만 나오던 것
  - ERD: 방향 미상 추론 관계를 부모·자식 관계처럼 그리던 것, `cardinality`·`unique`·`nullable` 미반영, `classDef`의 `6 4`가 `64`로 읽혀 점선이 깨지던 것, 테이블의 `primaryKey`·`uniqueKeys`, 테이블 주석, 정의 없는 테이블의 참조 컬럼, 뷰 포함 선택
  - 클래스 다이어그램: 인터페이스 멤버가 package-private로 나오던 것, 라벨의 `<…>`를 Mermaid가 지우는 것, 합성 멤버·익명 타입·외부 타입 필터, 범위 밖 상위 타입, 외부 패키지가 목록을 덮던 것
  - 진단·미해결: 같은 미해결 참조가 진단·노드·엣지 3행으로 나오던 것(45행 → 미해결 13건 + 진단 20건), 분류 필터
  - 탐색·상세: 참조 노드의 이름 복원, 주석·URL 원문 검색, 꼬리표와 요약, 엣지 속성 펼치기와 노드 ID 링크

- [x] T12. (계획에 없던 작업) 뷰어를 표준 산출물(`codeatlas-export`)의 규칙·표기와 맞춤 (F-016-R17~R22, AC28~AC40). 재현 테스트 `crud-parity.test.js`, `calltree-parity.test.js`, `new-attrs.test.js`, `viewer-parity.test.js`, 산출물 대조 `sample-export.test.js`(도우미 `tools/export-files.js`, `tools/export-notation.js`)
  - CRUD: 엔드포인트·루틴 기준 추가(네 기준), 루틴·트리거를 거친 간접 접근의 소문자, CALL 구문 행, `(동적)` 열과 동적 구문 목록, 범례, 표준 산출물과 같은 CSV. 실제 샘플의 네 CSV와 바이트까지 같다
  - 콜트리: 구현 전개를 인터페이스·추상 메서드로 한정, 본문 없는 중간 메서드 건너뛰기, 빈 이름 후보, `UPDATE OF <컬럼>` 트리거와 `[trigger?]`, `CALLS(super)`, 스텝의 타입·빈 전개, `NEXT` 흐름 순서(위상 순서), 컬럼 엣지만 있는 테이블 줄, 신뢰도는 가장 확실한 것. 샘플에서 처음 견줬을 때의 차이는 `(super)` 표시 5곳뿐이었고, 나머지는 샘플에 없는 모양이라 조각 테스트로 맞췄다
  - 위 방향 주입 좁히기(남은 일에서 옮김): 구현의 호출자에 "인터페이스 경유" 호출자를 바로 넣고 다른 구현을 주입받는 호출자와 `super` 호출을 뺀다. 전에 있던 "재정의 대상" 줄은 없어졌다(AC8 갱신)
  - ERD: 고정 컬럼 라벨, 라벨 표기를 `컬럼 (추론 n건)`으로, `erd.mmd`와 관계·카디널리티 대조
  - 상세: 엣지 한 줄 요약(`REFERENCES`, `JOINS`, 미해결 호출의 인자 수, `viaProp`, redirect·forward), `typeRefs`·`redirects` 링크, `indexes` 여러 줄, `sequence=true` 꼬리표, 메서드 요약의 반환형 중복 수정
  - 진단·미해결: "Java 호출자 없는 구문", "동적 구문" 보기, 미해결 호출의 인자 수
  - 확인: 실제 샘플의 `viewer.html`을 `http://localhost`로 띄워 CRUD 네 기준과 범례·동적 구문 목록, 콜트리의 `CALLS(super)`·"구현(주입)"·"인터페이스 경유", ERD의 고정 컬럼 라벨(Mermaid가 그린다), 상세의 요약과 여러 줄 `indexes`, 진단의 새 보기를 보았다(콘솔 오류 없음). 표준 산출물에 없는 "호출하는 쪽" 트리는 `codeatlas calltree --callers --format json`의 결과와 노드 ID로 따로 견줬다(테이블·뷰 20개, 메서드 70개, 구문·루틴·엔드포인트 등 60개, JS 40개; 다른 점은 spec 5절에 적은 두 가지뿐)

- [x] T13. (계획에 없던 작업) 2차 수정(2026-10-11)에서 바뀐 표준 산출물의 규칙과 그래프 계약에 뷰어를 다시 맞춤 (F-016-R23~R32, AC41~AC58). 시작할 때 깨져 있던 대조 테스트는 콜트리 6개였다(접근자·소스 없는 메서드를 표준 산출물이 기본으로 접게 된 것). 재현 테스트 `calltree-context.test.js`, `erd-areas.test.js`, `align-detail.test.js`, `viewer-align.test.js`, 산출물 대조 `sample-export.test.js`(도우미 `tools/export-tree.js`, `fixtures/java-graph.js`, `fixtures/align-graphs.js`)
  - 콜트리(`core/calltree.js`를 `FlowNavigator`의 2차 수정본에 맞춰 다시 씀): 수신자 문맥으로 상속 `EXECUTES`·리플렉션 `CALLS` 좁히기와 `[?]`·`[reflect]`, 호출하는 쪽의 대칭 판정, 구현 구체 타입으로의 `INJECTS`도 주입 후보, 추론 `OVERRIDES` 사슬의 `~`, `super` 도착 시 구현 전개 안 함, 멀티모듈 엔드포인트의 같은 모듈 핸들러 선택과 `ROUTES_TO(모듈 …)`, `crossModule` `MATCHES`의 추론 표시, `[before]`(modelAttribute), 자식 순서(걸음 단위: `[before]` → 호출 지점 순 → ID 순), 접근자·소스 없는 메서드 접기의 기본값과 토글·"접힘 n", 데이터 적재 구문 기본 제외와 토글, 시작 노드 묶음(엔드포인트, 배치 잡, 필터·인터셉터)
  - CRUD: 데이터 적재 구문 기본 제외(토글, 뺀 수 표시), 엔드포인트 행 이름의 `resolvedPath` 표기, 엔드포인트 기준에 수신자 문맥 반영(방문 표시의 키에 문맥), 엔드포인트 기준을 고를 때 나눠 계산 + 진행 표시(`createJob`)
  - ERD: 표준 산출물과 같은 ERD 모델·Mermaid 글자(`exportModel`, `exportMermaid`)와 영역 나누기(`splitAreas`), 범위에 "영역" 추가(다른 영역의 이웃은 이름만), 상한·글자 수 초과 때 "영역으로 보기"
  - 상세·탐색: 새 attrs의 꼬리표·요약·엣지 요약·타입 이름 링크, 마무리 단계 미해결 노드의 분류 꼬리표("기대한 종류" 폐기), 분석 안 한 벤더·압축 파일 기본 숨김, 필터·인터셉터 타입에서 진입 메서드의 콜트리로
  - 진단·미해결: 분류를 `report.md`와 같은 묶음으로(마무리 단계 노드의 `category`), 코드 설명, 위치 없는 진단("프로젝트 전체")과 폴더 위치, 전체 메시지 펼치기, "DDL에 없는 테이블"·"데이터 적재 스크립트" 보기
  - 큰 그래프: 엣지가 많은 노드의 종류별 엣지 목록(`index.outOf`·`inOf`), 타입 계층·재정의·판정 캐시. 측정값은 spec 6절(156MB 그래프에서 가장 긴 연산이 JSON 읽기 0.3초)
  - 대조: 샘플과 공개 프로젝트 8개의 산출물에서 네 CSV(바이트), 모든 엔드포인트 트리, `filters.md`, `erd.mmd`·영역, `report.md`의 절이 같다. 뷰어 쪽에서 고칠 차이는 남지 않았다. 표준 산출물 쪽에서 찾은 것 1건(값 자리에서 함수를 부르는 데이터 적재 구문이 `table-statement.csv`에 행으로 남는다)은 보고했다(spec 5절)
  - 확인: 실제 샘플과 jeesite·전자정부 템플릿·공통컴포넌트(87MB)의 `viewer.html`을 `http://localhost`로 띄워 콜트리(수신자 문맥으로 좁힌 트리, "요청 전"·"접힘" 꼬리표, 필터·인터셉터 묶음), CRUD의 엔드포인트 기준 진행 표시, ERD의 영역(Mermaid가 다른 영역의 이웃을 이름만 그린다), 진단의 새 보기와 폴더 위치, 탐색의 벤더 숨김(1,472개), 상세의 새 요약을 보았다(콘솔 오류 없음)
  - 표준 산출물에 없는 "호출하는 쪽" 트리는 `codeatlas calltree --callers --format json`의 결과와 노드 ID·표식·순서로 따로 견줬다(상속 구문, 리플렉션 대상, 재정의 메서드, `@ModelAttribute` 메서드, 테이블, 엔드포인트, 루틴, 스텝에서 시작. jeesite 43개 트리 2,921줄, zheng 28개 726줄, 전자정부 템플릿 24개 1,645줄, 샘플 31개 514줄). 다른 점은 spec 5절에 적은 "뷰의 정의 쿼리에서 뷰로 올라가기"뿐이었다
  - 화면 테스트의 가짜 DOM이 `null` 자식을 조용히 버리던 것을 브라우저처럼 글자로 붙이게 고쳤다(없는 자식을 그대로 넘긴 화면 코드 2곳을 이것으로 찾아 고쳤다)

## 남은 일
- [x] `samples/sample-shop` 분석 결과(graph.json)로 뷰어를 열어 픽스처와 다른 점이 없는지 확인 (T11)
- [x] (계획에 없던 작업, 2026-10-11) 사용자가 `viewer.html`을 브라우저로 열자 "뷰어를 시작하지 못했다: Failed to execute 'add' on 'DOMTokenList'"가 났다. 탐색 화면이 `classList.add('hide-client-routes none')`처럼 공백이 든 이름을 한 번에 넣은 것이 원인이고, 가짜 DOM이 너그러워 테스트가 놓쳤다. 가짜 DOM을 실제처럼 엄격하게 바꿔 실패를 재현한 뒤(`strict-dom.test.js`, 기존 테스트 30여 개도 실패) `withClass`가 이름을 하나씩 넣게 고쳤다 (F-016-R44, F-016-AC82). 고친 뒤 실제 브라우저(`http://localhost`, 앱 내장 브라우저)로 샘플의 탐색·ERD·클래스 다이어그램·콜트리·CRUD·진단 여섯 화면이 콘솔 오류 없이 뜨는 것을 확인했다
- [x] `file://`로 직접 열어 확인 (T11에서는 `http://localhost`로 확인했다). 4차 수정에서 브라우저 도구로 다시 시도했지만 `file://` 주소를 열지 못했다. 대신 `file:` 환경을 흉내 내 스크립트를 실행하는 테스트(F-016-AC71)를 뒀다. `http://localhost`로는 실제 브라우저에서 여섯 화면을 확인했다(2026-10-11). `file://` 주소로 여는 것은 소유자가 자기 브라우저에서 샘플 결과를 직접 열어 잘 뜨는 것을 확인했다(2026-10-11)
- [x] 콜트리에서 위로 올라갈 때의 주입 좁히기 (T12, F-016-R19)
- [ ] 표준 산출물과 일부러 다르게 둔 것(spec 5절: 뷰 → 정의 쿼리, JS 함수에서 모듈로 올라가는 조건)을 어느 쪽으로 맞출지 정한다
- [ ] 영역 나누기의 글자 수 한도를 분석 때의 옵션(`export.mermaid.maxChars`, `graph.json`의 `project.options.extra`)에서 읽을지 정한다(지금은 기본값 45,000 고정)
- [ ] 표준 산출물의 "호출하는 쪽" 트리는 파일로 나오지 않아 조각 테스트와 손으로 돌린 대조(T13)로만 맞췄다. CLI의 `calltree --callers --format json`과 견주는 것을 자동 테스트로 둘지 정한다(실행 jar가 필요하다)
- [ ] JS 테스트는 Maven 빌드에 묶여 있지 않다. 묶을지 정한다(빌드에 Node가 필요해진다)

## 3차 수정 (2026-10-11)
- [x] F-016-AC59~AC65(`request-flow.test.js`) → `js/core/calltree.js`(요청 진입, 화면 이동·레이아웃에서 멈춤, 접근자 조건, 제네릭 상위 클래스, 클라이언트 라우트, 묶음 줄), `crud.js`(redirect·레이아웃을 따라가지 않음), `diagnostics.js`(클라이언트 라우트 제외, `requestStats`), `erd.js`(관계 영역·허브, 방향 미상의 까닭, DDL에 없는 컬럼) → 통과 (F-016-R33). core는 export를 먼저 고친 뒤 옮겼고, 실패는 대조 테스트(`sample-export.test.js`가 새 산출물과 어긋남: 9건)로 확인했다
- [x] 대조: 샘플(200개 모두 통과), 2차 재검증 그래프의 공통컴포넌트(엔드포인트 1,202개, 견준 줄 24,549개)와 jeesite, 새 java·link 그래프의 jeesite(222개, 10,330줄)·zheng·enterprise, 최종 jar로 다시 분석한 jeesite에서 9개 대조 테스트가 모두 일치
- [x] 화면: 묶음 줄·레이아웃·리다이렉트·화면 내 이동 표시, "레이아웃 펼치기", 탐색의 클라이언트 라우트 숨기기, ERD 영역 안내의 허브
- [x] 남은 일이던 것(화면 쪽 조각 테스트, `JOINS.wrapped`·`REFERENCES.fromUpgrade` 표시, `api=tag`·`optionKey=arg` 문구, `minOf` 표시) → 4차 수정

## 4차 수정 (2026-10-11, 작업마다 재현 테스트 먼저)
- [x] F-016-AC66(`inferred-marks.test.js` 6개) → 실행해 단언 실패 확인(칸에 `?`가 없음) → `calltree.js`의 `inference`·`singleTarget`, `crud.js`의 두 상태 탐색·`INFERRED_MARK`·범례 → 통과 (F-016-R34). 기존 기대값을 바꾼 곳: `calltree-context`(AC42), `request-flow`(AC60), `viewer-parity`(AC28)
- [x] F-016-AC67·AC68·AC70(`round4-detail.test.js` 8개) → 실행해 7개 실패 확인(고정 값 테스트는 처음부터 통과: 구현이 이미 맞았다) → `graph.js`의 요약·꼬리표·`directionReason`·`undeclaredColumnsOf`, `erd.js`의 upgrade 라벨·범례, `diagnostics.js`의 `columnMismatches`·`requestStatistics` → 통과 (F-016-R35, R36, R38). zheng의 실제 그래프(`fromUpgrade` 4건)에서 대조 테스트가 먼저 깨진 것으로도 확인했다
- [x] F-016-AC66·AC68~AC70 화면(`viewer-round4.test.js` 7개) → 실행해 새로 만든 요소의 테스트 4개 실패 확인 → 진단 보기, 콜트리의 "화면 내 이동 숨기기", CRUD 칸의 `inferred`, 상세의 절 → 통과. 이미 있던 화면 요소를 고정하는 테스트 3개(레이아웃, 묶음 줄, redirect·forward)는 처음부터 통과해서, 화면 코드를 일부러 망가뜨려(토글이 옵션을 바꾸지 않게, 꼬리표 글자를 다르게, 꼬리표를 붙이지 않게) 셋 다 실패하는 것을 본 뒤 되돌렸다 (F-016-R37)
- [x] F-016-AC71(`file-protocol.test.js` 4개) → 뷰어는 처음부터 통과(막히는 API를 쓰지 않는다). 덫이 동작하는지는 덫을 건드리는 스크립트를 넣는 테스트로 확인했다 (F-016-R39)
- [x] F-016-AC72(`round5.test.js` 8개) → 실행해 8개 모두 실패 확인 → `calltree.js`(끝 위치 순, `MATCHES` 묶음, 순수 생성자·빌더, `sharedPages`·`noteOf`), `diagnostics.js`(요청 통계의 새 구분·미연결 목록, 보충한 구문, 시스템 카탈로그), `graph.js` → 통과 (F-016-R40). 기존 기대값을 바꾼 곳: AC47(생성자가 접힘), AC64(위로 올라가다 만난 데코레이터는 `[shared]`), AC19(생성자 줄을 보려면 접근자 보기)

## 5차 수정 (2026-10-11, 작업마다 재현 테스트 먼저)
- [x] F-016-AC73·AC74(`round6.test.js` 7개) → 고치기 전의 `calltree.js`로 실행해 6개 실패 확인(벤더 메시지 테스트는 처음부터 통과: 구현이 문구에 기대지 않는다) → `calltree.js`(`matchModule`·`inModule`·`declaredIn`·`severalHandlers`, `resolvedAnotherWay`, `relationDetail`, 추론의 까닭, `INCLUDES` 묶음), `graph.js`(`requestLabel`·`requestMethod`, 꼬리표·요약·엣지 요약, `internalAttrs`), `diagnostics.js`(`resolvedBy` 제외, 미연결 요청의 이름), `detail.js`(접힌 "분석 중간 정보", 요청 상세의 제목), `viewer.css` → 통과
- [x] 실제 그래프 9개(sample-shop과 공개 프로젝트 8개를 새 분석기로 다시 분석)에서 표준 산출물 ↔ 뷰어 대조(`CODEATLAS_OUT`) 통과
- [x] design.md 반영(spec 8절. 문서 정리 라운드에서 반영함, 2026-10-11)

## 6차 수정 (2026-10-11, 4차 재검증 후속)
- [x] F-016-AC75~AC81(`round7.test.js` 7개) → 고치기 전의 뷰어로 실행해 7개 모두 실패 확인 → `graph.js`(`unresolvedSelfSubmitMethod`·`unresolvedSelfSubmitLabel`·`borrowedDataLoadNote`, 짧은 이름, 꼬리표·요약, `RENDERS` 요약의 `narrowedBy`), `calltree.js`(`matchFlags`의 모듈 표시, `declaredInSeveralModules`·`requestEntriesInto`, `relationDetail`·`inferenceOf`의 `narrowedBy`, 묶음 줄 이름, `labelOf`·`noteOf`), `diagnostics.js`(`unresolvedDifference`, 미해결 행의 대상 이름, `borrowedStatements`에서 적재 묶음 제외, 적재 파일의 `borrowedNote`·요약의 `borrowedText`), `erd.js`(추론 관계의 부모 쪽, 머리 주석, 시스템 카탈로그 제외, 범례), `view-calltree.js`·`view-diagnostics.js`·`view-erd.js`·`detail.js`(표시) → 통과 (F-016-R43)
- [x] 기대값을 바꾼 기존 테스트: ERD의 `||..` → `|o..`와 머리 주석(`erd.test.js`, `erd-direction-only.test.js`, `erd-areas.test.js`, `new-attrs.test.js`, `real-shapes.test.js`, `viewer-html.test.js`), 범례의 `ends`(`true` → `'optional'`)와 그림(`erd-direction-only.test.js`, `real-shapes.test.js`)
- [x] 테스트 도구: `tools/export-tree.js`(풀어 쓴 이름 `labelText`, selfSubmit 묶음 줄), `tools/export-notation.js`(문맥 없는 미해결 selfSubmit 이름)
- [x] sample-shop의 표준 산출물(`codeatlas-cli/target/final-out`)을 새 jar로 다시 내고 대조 테스트 통과(JS 263개)

## 상세의 뒤로·앞으로 (2026-10-11, 사용자 요청)
- [x] F-016-AC83~AC87(`trail.test.js` 7개) → 고치기 전의 뷰어로 실행해 7개 모두 실패 확인 → `core/trail.js`(자취), `widgets.js`(`createTrailNav`: 단추와 풍선말), `detail.js`(상세 안의 노드 링크를 `opts.follow`로), `view-explore.js`(고른 경로 `list`·`follow`·`trail`·`address`에 따라 자취와 주소를 맞춤), `app.js`(서랍의 자취, `pushHash`) (F-016-R45)
- [x] 실제 브라우저 확인: jeesite 결과를 `http://localhost`로 띄워 탐색의 단추, 브라우저의 뒤로·앞으로 가기, 서랍의 단추를 확인(콘솔 오류 없음). `file://`은 확인에 쓴 브라우저 도구가 열지 못해 `file-protocol.test.js`(덫에 걸린 접근 없음)로 갈음
- [x] JS 272개(그중 28개는 실제 그래프와 표준 산출물이 있을 때만 돈다) 통과, `mvn -q -pl codeatlas-viewer test` 통과

## 상세의 SQL 정리 (2026-10-12, 사용자 요청)
- [x] F-016-AC88·AC89(`sql-format.test.js` 10개) → 고치기 전의 뷰어로 실행해 실패 확인(`core/sqlformat.js` 없음) → `core/sqlformat.js`(낱말 단위 정리기: 절 줄바꿈, 쉼표·AND·OR 들여쓰기, 하위 쿼리 들여쓰기, 괄호·CASE 안은 한 줄, 문자열·주석·자리표시·달러 인용 보존), `assets.txt`, `detail.js`(`sql` 값의 정리본과 "원문"·"정리" 단추), `viewer.css` → 통과 (F-016-R46)

## 컬럼 쌍이 여럿인 조인·관계 (2026-10-12, 사용자 요청)
- [x] F-016-AC90~AC92(`column-pairs.test.js` 3개) → 고치기 전의 뷰어로 실행해 3개 모두 실패 확인 → `graph.js`(`columnPairs`, 요약의 `컬럼 n쌍`, 엣지 항목의 `pairs`·`fullSummary`), `detail.js`(엣지 줄 아래의 쌍 표, 풍선말), `viewer.css`(`.edge-pairs`), `erd.js`(`pairText`: 쌍마다 이은 관계 라벨) → 통과 (F-016-R47)

## 압축 내장 (2026-10-12, 사용자 보고: 큰 프로젝트의 viewer.html이 100MB에 달한다)
- [x] F-016-AC93~AC95(`ViewerExporterTest`의 AC1·AC2·AC3·AC93, `embed.test.js` 8개) → 고치기 전의 코드로 실행해 실패 확인(Java 6개, JS는 `core/embed.js` 없음) → `ViewerHtml`(`writePacked`: `GZIPOutputStream` + `Base64`로 흘려 쓰기, Mermaid도 같은 길), `template.html`(블록을 `type="text/plain"`으로), `core/embed.js`(`inflateText`: `DecompressionStream`), `app.js`(푼 뒤에 켜기, `CodeAtlas.ready`, 푸는 중 안내), `ui/diagram.js`(Mermaid를 처음 그릴 때 풀기, 실행 실패 문구) → 통과 (F-016-R48)
- [x] 테스트 도구: `tools/assemble.js`(`pack`·`unpack`), `tools/boot.js`(`boot`가 `CodeAtlas.ready`를 기다린다. `launch`는 기다리지 않는다. head에 붙인 script를 실행한다), `tools/fake-dom.js`(`atob`·`TextDecoder`·`DecompressionStream`), 화면 테스트의 `boot(…)` 호출을 `await`로
- [x] 기대값을 바꾼 기존 테스트: 데이터·Mermaid 블록을 풀어서 읽는다(`ViewerExporterTest`, `viewer-html.test.js`, `file-protocol.test.js`, cli의 `CodeatlasJarIT` F-001-AC28)
- [x] 실제 브라우저 확인: 이 저장소의 분석 결과(graph.json 69.5MB → `viewer.html` 6.7MB)를 `http://localhost`로 띄워 뷰어가 켜지고 ERD가 그려지는 것을 확인(콘솔 오류 없음)
- [x] `mvn -q install` 통과, JS 293개(그중 28개는 실제 그래프와 표준 산출물이 있을 때만 돈다) 통과

## 뷰어 내장 형식 (2026-10-12, 압축 내장의 후속)
- [x] core: `GraphJsonTest`(F-001-AC15 `exposesFileOrder`) → 컴파일 실패 확인 → `GraphJson.orderedNodes`·`orderedEdges`·`orderedDiagnostics`·`projectJson` 공개(`write`가 같은 것을 쓴다) → 통과
- [x] F-016-AC96(`ViewerExporterTest`: `writesCompactForm`, AC1·AC3·AC93의 테스트가 되돌린 그래프를 `graph.json`과 견준다) → 고치기 전의 코드로 실행해 6개 실패 확인 → `CompactGraphJson`(Jackson `JsonGenerator`로 흘려 쓰기), `ViewerExporter`, `pom.xml`(jackson-databind를 직접 의존으로) → 통과 (F-016-R49)
- [x] F-016-AC97·AC98(`compact.test.js` 6개) → `core/compact.js`가 없어 실패 확인 → `core/compact.js`(`expand`), `assets.txt`, `app.js`(푼 JSON을 되돌려 색인) → 통과 (F-016-R49)
- [x] 테스트 도구: `tools/compact.js`(`compactGraph`: Java와 같은 규칙. AC98이 Java의 출력과 견준다), `tools/assemble.js`(`assemble`은 줄인 형식을 넣고 `assembleRaw`는 받은 글자를 그대로 넣는다)
- [x] 실제 브라우저 확인: 이 저장소의 분석 결과(graph.json 69.8MB → `viewer.html` 4.9MB)를 `http://localhost`로 띄워 뷰어가 켜지고 콜트리와 ERD가 그려지는 것을 확인(콘솔 오류 없음)
- [x] `mvn -q install` 통과, JS 299개(그중 28개는 실제 그래프와 표준 산출물이 있을 때만 돈다) 통과

## 엣지 풍선과 탐색 화면의 나눔선 (2026-10-12, 사용자 보고·요청)
- [x] 재현: 전자정부 공통컴포넌트의 `COMTHDBMNTRNGLOGINFO` 상세(칸 너비 643px)에서 `JOINS` 요약은 758px 가운데 112px, 위치는 603px 가운데 281px만 보이는 것을 확인(같은 테이블로 가는 여섯 줄, 같은 매퍼 파일의 `READS`·`WRITES` 줄이 같아 보인다)
- [x] 첫 시도(줄을 두 줄로 늘려 요약과 `/파일:줄`을 다 보임)는 사용자 검토에서 되돌렸다: 폴더는 여전히 잘리고 상세가 길어진다. 풍선으로 다시 한다
- [x] F-016-AC99·AC100(`edge-pop.test.js` 4개) → 고치기 전의 뷰어로 실행해 모두 실패 확인 → `detail.js`(풍선 `ui.edgePop`: `popCard`·`popPlace`·`popAttach`, 줄의 `title` 제거와 `data-id`), `app.js`(Esc는 고정한 풍선부터, 화면을 바꾸거나 서랍을 닫으면 풍선도 닫음), `viewer.css`(`.edge-pop`) → 통과 (F-016-R50)
- [x] 풍선말용이던 `fullSummary`와 `edgeSummary`의 `inlinePairs`를 `graph.js`에서 빼고 `column-pairs.test.js`(AC90·AC91)와 `trail.test.js`(링크의 ID를 `data-id`로 읽음)를 맞춤
- [x] F-016-AC101(`edge-pop.test.js` 1개) → 실패 확인 → `widgets.js`(`createSplitter`), `view-explore.js`, `viewer.css`(`.splitter`, `.view.resizing`) → 통과 (F-016-R51)
- [x] 실제 브라우저 확인: 풍선(마우스 올리기, 눌러 고정, Esc), 좁은 창의 서랍(줄 아래에 뜸), 나눔선 끌기(상세 641px → 897px), 라이트·다크, 콘솔 오류 없음
- [x] `mvn -q install` 통과, JS 304개(그중 28개는 실제 그래프와 표준 산출물이 있을 때만 돈다) 통과

## 마무리
- [x] 모든 수용 기준 통과 확인 (JS 193개 — 그중 26개는 실제 그래프와 표준 산출물(`codeatlas-cli/target/final-out/`)이 있을 때만 돈다, Java 7개)
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경 (features.md의 F-016 행이 완료인 것을 확인함, 2026-10-11)
- [x] 구조가 바뀌었으면 architecture.md 갱신 (해당 없음)
