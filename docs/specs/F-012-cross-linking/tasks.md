---
type: tasks
id: F-012
updated: 2026-10-11
---

# F-012 교차 연결 작업 목록

## 구현 (작업마다 테스트 먼저)
- [x] T1. `DbObjectNormalizer`(order 100): AC1~AC5 테스트 → 실패 확인 → 뷰·시노님 정규화 구현 → 통과 (F-012-R1)
- [x] T2. `DbObjectNormalizer` 루틴: AC6~AC8 테스트 → 실패 확인 → 구현 → 통과 (F-012-R2, R3)
- [x] T3. `MapperBindingLinker`(order 200): AC9~AC11 테스트 → 실패 확인 → 구현 → 통과 (F-012-R4, R5, R6)
- [x] T4. 경로 패턴과 엔드포인트 색인(`PathPattern`, `EndpointMatcher`), `HttpMatchLinker`(order 210): AC12~AC17 테스트 → 실패 확인 → 구현 → 통과 (F-012-R7, R8, R9)
- [x] T5. `ViewLinker`(order 220): AC18~AC20 테스트 → 실패 확인 → 구현 → 통과 (F-012-R10, R11)
- [x] T6. `BeanReferenceLinker`(order 230): AC21, AC22 테스트 → 실패 확인 → 구현 → 통과 (F-012-R12)
- [x] T7. `InjectionCandidateLinker`(order 240): AC23 테스트 → 실패 확인 → 구현 → 통과 (F-012-R12)
- [x] T8. `META-INF/services/io.codeatlas.core.spi.Linker` 등록과 `ServiceLoader` 순서 테스트 (F-012-R1~R12)
- [x] T9. (감사 후속 U1) AC24 테스트 → 실패 확인 → `InjectionCandidateLinker`가 `candidates`가 하나인 주입을 구현 타입으로 잇는다 → 통과 (F-012-R12 고쳐 씀). AC23 테스트 `skips`에서 "candidates가 있는 엣지는 건너뛴다" 단언을 뺐다
- [x] T10. (감사 후속) AC25 테스트 → 실패 확인 → `ViewLinker`가 메서드 attrs `redirects`·`forwards`를 처리한다 → 통과 (F-012-R13). java 쪽 attrs가 아직 없어 손으로 만든 그래프로만 확인했다
- [x] T11. (뷰어 검증 후속) AC26 테스트 → 실패 확인 → `ExternalReferenceMarker`(order 90)와 `META-INF/services` 등록 → 통과 (F-012-R14)

- [x] T12. (공개 프로젝트 검증 B10) 프로퍼티 조회 공용 헬퍼 `ProjectProperties`와 모듈 루트 헬퍼 `ModuleRoots`: AC39 테스트 → 구현 → 통과 (F-012-R24)
- [x] T13. (B5) `PathPattern`·`EndpointMatcher` 다시 씀: AC27~AC36 테스트(`HttpMatchRulesTest`) → 실패 확인(13개 중 11개 실패) → 자리표시자 치환·미지 고정 조각·`strippedPlaceholder`, 섞인 세그먼트, 맨 앞 `{*}`, 상대 URL의 기준 디렉터리·고정 세그먼트 접미, 끝 슬래시, 루트·접두 제거 조건, 모듈 경계 → 통과 (F-012-R15~R21). `HttpMatchLinker`의 order를 225로 옮김. 기대값을 바꾼 기존 테스트: `HttpMatchLinkerTest.wildcardSegments`(AC13: `/orders/`가 `/orders`와 맞는다), `LinkerRegistrationTest`(순서)
- [x] T14. (B10-a) `ViewLinker`: AC37 테스트 → 실패 확인 → 리졸버 prefix·suffix의 자리표시자 치환 → 통과 (F-012-R22). redirect·forward의 모듈 경계와 상대 대상(AC33, AC36) 포함
- [x] T15. (B10-b, B21) `BeanReferenceLinker`·`BeanSupport`: AC38, AC41 테스트 → 실패 확인 → 자리표시자 빈 이름 치환과 진단, 모듈·테스트 소스로 정의 좁히기 → 통과 (F-012-R23, R26)
- [x] T16. (B6) `MapperBindingLinker`: AC40 테스트 → 실패 확인 → 물려받은 메서드의 `EXECUTES`에 `inherited=true`, `namespace`를 바이너리 이름으로 → 통과 (F-012-R25)
- [x] T17. 공개 프로젝트 graph.json 재연결 검증(scratch 실행기): 링커가 만든 엣지를 걷어내고 수정 전 jar·수정 후 코드로 각각 다시 돌려 비교. 샘플 프로젝트 그래프는 전후가 완전히 같다(MATCHES 39, RENDERS 5)
- [x] T18. (실전 검증 2차 후속) `InjectionCandidateLinker`: AC42 테스트 2개 → 실패 확인 → "stereotype이 있는 타입 = 기본 빈 이름의 빈"을 "`DEFINES_BEAN` 또는 `beanName`이 있는 타입"으로 → 통과 (F-012-R27). 기존 AC23 테스트의 픽스처는 java가 실제로 내는 것처럼 `stereotype`과 `beanName`을 함께 넣게 고쳤다. `BeanSupport`는 `stereotype`을 보지 않아 고칠 것이 없었다(`MapperBindingLinker`의 `stereotype=mapper` 확인은 값 하나를 보는 것이라 그대로)
- [x] T19. (실전 재검증 2차 후속, N7) AC43 테스트 3개 → 실패 확인 → `ViewLinker.sites`·`callSite`로 엣지 위치를 호출 지점으로 → 통과 (F-012-R28)
- [x] T20. (N9-a) AC44 테스트 → 실패 확인 → `HttpMatchLinker`가 `clientRoute=true` 요청을 건너뜀 → 통과 (F-012-R29)
- [x] T21. (Tiles) AC45~AC47 테스트 5개 → 실패 확인 → `ViewLinker.collectTiles`·`Run.tiles` → 통과 (F-012-R30). jsp가 attrs를 아직 남기지 않아 손으로 만든 그래프로만 확인했다(형식은 조정자가 확정)
- [x] T22. (N28-a, d, N27, N11) AC48~AC52 테스트 → 실패 확인 → `MapperBindingLinker.resolvePrefixes`, `ViewLinker`(후보 뷰 이름, 대상의 자리표시자 치환, 엔드포인트의 `viewNames`, `contextRootPage`) → 통과 (F-012-R31~R35)
- [x] T23. (N12) AC53 테스트 → 실패 확인 → `EndpointMatcher.narrow`가 요청 쪽 모듈에 핸들러가 없어도 `crossModule`로 → 통과 (F-012-R36). AC36 테스트의 "핸들러가 없는 모듈: 판단하지 않는다" 단언을 새 기준으로 고쳤다
- [x] T24. (B21 후속) 2차 그래프(zheng)의 `INFERRED` `INJECTS`(`resolvedFrom`) 59건 가운데 주입받는 타입과 구현 타입의 모듈 루트가 다른 것 55건을 뽑아 확인: 같은 모듈에 후보가 있었는데 다른 모듈 것을 고른 경우 0건(전부 `…-admin`·`…-web` → `…-rpc-service` 구조). 코드 변경 없음
- [x] T26. (web·jsp 3차 수정 대응, 확인) `HttpMatchRulesTest`에 확인 테스트 4개: `clientRoute`는 `api`(route·navigation)와 무관하게 attr만으로 판정(AC44), `api=tag` 요청의 `strippedPlaceholder` 추론(AC29), 끝이 `/`인 EL 삼항 후보(AC34), `ANY` 요청이 메서드만 다른 엔드포인트 여럿에 맞으면 전부 `INFERRED` + `ambiguous`(AC16). 모두 코드 변경 없이 통과(기존 규칙이 그대로 적용된다)
- [x] T27. (실전 재검증 3차 후속, M5) AC54~AC56 테스트(`HttpMatchModuleTest`) → 실패 확인 → `EndpointMatcher.Endpoint.viewModules`·`declaringModules`, `narrow`(같은 모듈 선언, 독립 배포 단위), `ModuleRoots.isWebApp`·`markWebApp`, `finish`의 `module`, `ViewLinker.declaresViewOnly` → 통과 (F-012-R37, R38)
- [x] T28. (M1, selfSubmit) AC57, AC58 테스트 → 실패 확인 → `EndpointMatcher.Request.methods`·`methodFits`·`matchSamePath`, `HttpMatchLinker.selfSubmit` → 통과 (F-012-R39, R40). web·jsp가 attrs를 아직 남기지 않아 손으로 만든 그래프로만 확인했다
- [x] T29. (M7, M13-a) AC59~AC61 테스트(`MapperBindingLinkerTest`) → 실패 확인 → `MapperBindingLinker.operationFits`·`resolvePrefixes`(노드 `resolvedBy`)·`resolveArgumentLiterals` → 통과 (F-012-R41~R43)
- [x] T30. (M13-c) AC62 테스트(`ViewLinkerTest` 2개) → 실패 확인 → `ViewLinker.dynamicDirectory`·`findWithDirectory` → 통과 (F-012-R44)
- [x] T31. (후보 하나짜리 정리) AC63 테스트 → 실패 확인 → `ViewLinker.linkDispatch`가 대상 핸들러를 모은 뒤 둘 이상일 때만 `ambiguous` → 통과 (F-012-R45)
- [x] T32. zheng 전체 재실행(3차 jar의 다른 모듈 + 고친 java·link)에서 F-012-R44로 생긴 `RENDERS` 때문에 시안 페이지의 상대 링크(`login.html`)가 경로 변수 엔드포인트에 39건 새로 맞은 것을 발견 → AC64 테스트 → 실패 확인 → `EndpointMatcher.matchRelative`가 정적 파일 꼴의 상대 링크를 경로 변수에 맞추지 않음 → 통과 (F-012-R46)
- [x] T33. 공개 프로젝트 전후 대조(3차 그래프 대 재실행): zheng `crossModule` 54건(참 41, 거짓 13) → 41건(전부 참). 거짓 13건 가운데 11건(`500.jsp → GET /`)은 자기 모듈 매칭(`RESOLVED`, `module`), 2건(`/manage/index`)은 미해결. `module` 18건. 뷰 도우미 미해결 23곳 → 20곳이 동적 디렉터리로 `RENDERS`(`INFERRED` 21건: 19곳은 후보 하나, 1곳은 후보 둘), 1곳은 리졸버로 `RESOLVED`, 2곳(`/{*}/index.html`)은 후보가 22개라 미해결. jeesite 뷰 미해결 12 → 6(테마 뷰 6곳이 후보 둘씩 `RENDERS` 12건, 전부 `candidate`). 접두 구문 연결은 공통컴포넌트 3건 그대로(미해결 노드 1개에 `resolvedBy`), 인자 구문 연결은 공통컴포넌트 2건·enterprise 1건(`uploadExcel`). `ambiguous` 엣지 묶음 가운데 후보가 하나뿐인 것은 7개 프로젝트 모두 0
- [x] T34. spec 8.3절의 계약 변경을 design.md에 반영 (문서 정리 라운드에서 반영함, 2026-10-11)
- [x] T25. spec 8.2절의 계약 변경을 design.md에 반영 (문서 정리 라운드에서 반영함, 2026-10-11)
- [x] T35. (실전 재검증 4차 후속) AC65 테스트(`ViewLinkerTest` 4개, `ProjectPropertiesTest` 1개) → 실패 확인(4개. "근거 없으면 전과 같다" 1개는 처음부터 통과) → `ViewLinker.placeholderDirectory`·`dynamicDirectory`(프로퍼티 값, 모듈 디렉터리 이름으로 좁히기, `narrowedBy`), `ProjectProperties`가 자기 참조 값을 건너뜀 → 통과 (F-012-R47)
- [x] T36. AC66 테스트(`HttpMatchModuleTest` 3개) → 실패 확인(2개. "그리는 모듈이 하나로 정해지지 않으면 전과 같다" 1개는 처음부터 통과) → `EndpointMatcher.renderingModule`·`narrow` → 통과 (F-012-R48)
- [x] T37. 공개 프로젝트 전후 대조(4차 jar의 다른 모듈 + 고친 java·link): zheng `dynamicSegment` `RENDERS` 21건(20곳) → 22건(22곳, 전부 `narrowedBy=property`·후보 하나), 뷰 미해결 2건 해소, `crossModule` 41건 가운데 `GET /` 17건에 `module`. jeesite 12건은 그대로. design.md 3.4·3.5·3.9·7절 반영
- [x] T38. (실전 재검증 4차 후속, 추가) AC67 테스트(`HttpMatchModuleTest` 2개) → 실패 확인(1개. "근거 있는 접두·경로 변수는 전과 같다" 1개는 처음부터 통과) → `EndpointMatcher.doMatch`가 맨 앞 세그먼트가 엔드포인트의 첫 고정 세그먼트면 서블릿 매핑이 아닌 접두를 떼지 않음 → 통과 (F-012-R49). 코퍼스 전후: 추측으로 뗀 세그먼트 접두 `strippedPrefix` zheng 2 → 0(둘 다 죽은 링크), 재현 j1 2·r1 5는 그대로, 나머지 프로젝트는 0건. design.md 3.5·7절 반영

## 마무리
- [x] 모든 수용 기준 통과 확인
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경 (문서 정리 때 확인: features.md에 완료로 돼 있다)
- [x] 계약 추가 제안(spec 8절)을 최종 보고에 올림
- [x] spec 8.1절(공개 프로젝트 검증 후속)의 계약 변경을 design.md·architecture.md에 반영 (문서 정리 라운드에서 반영함, 2026-10-11)
