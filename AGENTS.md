# AGENTS.md

<!-- 짧게 유지한다. 코드에서 추론할 수 없는 규칙과 문서 위치만 적는다. -->

## 문서 위치
- 무엇을 왜 만드는가: `docs/prd.md`
- 기능 목록과 기능 ID: `docs/features.md`
- 기능별 명세·설계·작업: `docs/specs/F-xxx-이름/`
- 모든 모듈이 따르는 그래프 계약(노드·엣지 종류, ID 규칙, SPI): `docs/specs/F-001-analysis-pipeline/design.md`
- 구조: `docs/architecture.md`
- 결정 기록: `docs/decisions/`
- 문서 작성 규칙과 ID 규칙: `docs/README.md`
- 사용자용 사용법: 루트 `README.md`
- 변경 기록: 루트 `CHANGELOG.md`
- 검증용 샘플 프로젝트와 정답 목록: `samples/sample-shop/` (정답 형식은 그 폴더의 `README.md`)

## 작업 규칙
- 기능 작업을 시작하기 전에 해당 기능의 `spec.md`와 `tasks.md`를 읽는다.
- 새 기능은 `docs/features.md`에 행을 추가하고 `docs/_templates/`를 복사해 시작한다.
- 모르는 내용은 지어내지 않고 `[확인 필요]`로 표시한다.
- 요구사항 ID와 기능 ID는 한 번 부여하면 바꾸지 않는다. 폐기할 때는 상태만 바꾼다.
- `accepted` 상태의 ADR은 구속력이 있다. 충돌하면 멈추고 묻는다.
- 승인된 ADR의 본문은 고치지 않는다. 바꿀 때는 새 ADR을 만들고 이전 것을 superseded로 표시한다.
- 커밋 메시지에 기능 ID를 적는다. 예: `F-003: 생성자 호출 엣지 추가`
- 버그 수정이나 작은 변경은 문서를 만들지 않고 진행한다. 변경 기록은 이때도 남긴다(아래 "변경 기록").

## 변경 기록
- 사용자가 알아챌 수 있는 변경(기능 추가, 동작·출력·옵션의 변경, 버그 수정)은 그 변경과 같은 커밋에서 루트 `CHANGELOG.md`의 `[Unreleased]`에 항목을 추가한다.
- 분류는 추가, 변경, 수정, 제거 넷이다. 맞는 분류의 맨 위에 넣고, 분류 제목이 없으면 만든다.
- 한 항목은 `기능 ID: 사용자 관점에서 무엇이 달라졌는지 (날짜)` 꼴로 한두 문장이다. 구현 방법이 아니라 달라진 동작을 적고, 기존 결과가 달라지거나 사용법이 바뀌면 그 점을 밝힌다.
- 리팩터링, 테스트, 문서만 바뀐 커밋은 적지 않는다.
- 이미 적힌 항목은 고치지 않는다. 틀린 내용을 바로잡을 때만 고친다.

## 개발 방식: TDD
- 구현 코드를 쓰기 전에 실패하는 테스트를 먼저 쓰고, 실패를 확인한 뒤, 통과시키는 최소 구현을 하고, 리팩터링한다.
- spec의 수용 기준(F-xxx-ACn)마다 테스트가 하나 이상 있어야 하고, 테스트 이름이나 `@DisplayName`에 그 ID를 적는다.
- 버그를 고칠 때는 그 버그를 재현하는 테스트를 먼저 추가한다.
- 테스트가 통과하지 않는 상태로 작업을 끝내지 않는다. 통과시키지 못한 테스트는 지우거나 끄지 말고 보고한다.

## 이 프로젝트 고유 규칙
- 분석기는 해석하지 못한 참조를 버리지 않고 "미해결"로 기록한다. 추측으로 관계를 만들지 않는다.
- 추론으로 만든 관계(예: JOIN 조건에서 추론한 FK)는 근거 위치와 함께 `INFERRED`로 구분해 기록한다.
- 노드 ID와 엣지 종류는 그래프 계약 문서를 따른다. 계약을 바꿔야 하면 그 문서를 먼저 고친다.
- 그래프 계약 문서의 `### 3.8`(옵션 키 표)과 `### 3.9` 제목, 루트 `README.md`의 `--opt` 예시는 cli 테스트가 읽어서 대조한다. 제목이나 표 형식을 바꾸면 테스트가 깨진다.
- 분석 대상 프로젝트의 파일은 읽기만 한다.
- 파일 탐색과 읽기는 이 저장소, `~/.m2`, 임시 폴더, 공개 프로젝트 검증용 클론을 모아 둔 `~/workspace/codeatlas-corpus` 안에서만 한다. `find /`, `find ~`, `mdfind`, `/Volumes` 접근처럼 저장소 밖을 훑는 명령은 쓰지 않는다. 도구 설치 여부는 `which`로만 확인한다.
- 파일 하나의 파싱 실패가 전체 분석을 멈추게 하지 않는다.

## 빌드와 테스트
- 필요한 것: JDK 21 이상, Maven 3.9.
- 전체 빌드와 테스트: 루트에서 `mvn -q install`. 단위 테스트와 실행 jar 통합 테스트(`*IT`)를 돌리고 모든 모듈을 `~/.m2`에 설치한다.
- 샘플 프로젝트 정답 대조: `mvn -q -pl codeatlas-cli verify -Dsample.verify=report` 를 돌리면 `codeatlas-cli/target/sample-shop-report.md`에 종류별 통과율과 실패 목록이 나온다. `-Dsample.verify=report` 없이 돌리면 통과율 100%와 함정 위반 0을 단언한다.
- IDE의 Java 자동 빌드가 `target/`에 클래스를 써서 테스트 결과가 이상해 보이면(`Unresolved compilation problems`) `clean`을 붙여 다시 돌린다.
- 뷰어의 JS 로직 테스트는 Maven에 묶여 있지 않다: `node --test "codeatlas-viewer/src/test/js/*.test.js"` (Maven 테스트를 먼저 돌려야 생성된 `viewer.html` 검사까지 한다).
- 모듈 하나만 테스트: 루트에서 `mvn -q -pl codeatlas-<모듈> test`. 다른 모듈은 `~/.m2`에 설치된 것을 쓴다.
- 테스트 클래스 하나만: `mvn -q -pl codeatlas-core test -Dtest=GraphModelTest`
- core를 고친 뒤에는 `mvn -q -pl codeatlas-core install`로 `~/.m2`를 갱신해야 다른 모듈이 새 core를 본다.
- 실행: `bin/codeatlas analyze <projectDir> [--ddl <경로>] [--out <dir>]` (실행 jar는 `codeatlas-cli/target/codeatlas.jar`)
- 모듈 단위 테스트에서는 `io.codeatlas.core.testing.TestProject`로 가짜 프로젝트를 만들어 분석기와 링커를 돌린다.
